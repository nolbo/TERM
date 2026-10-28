//! One event loop owns guest processes. Viewer lifetime and capacity never gate PTY draining.

use crate::graphics_transport::{GraphicsTransport, Output as GraphicsOutput};
use std::collections::{HashMap, HashSet, VecDeque};
use crate::protocol::{encode_error, ErrorCode, Frame, FrameKind, Service};
use crate::pty::PtyError;
use crate::session::SessionRegistry;
use crate::session_event::SessionEvent;
use crate::session_protocol::SessionRequest;
use crate::session_response::{SessionDescriptor, SessionResponse};
use crate::vsock::{VsockConnection, VsockError, VsockListener};

pub struct SessionServer {
    client: Option<Client>,
    output: crate::output_buffer::OutputBuffer,
    output_cursor: String,
    poll: PollState,
    sessions: SessionRegistry,
    graphics: HashMap<String, GraphicsTransport>,
    pending: HashMap<String, VecDeque<PendingInput>>,
    pending_bytes: usize,
    staged_output: HashMap<String, Vec<u8>>,
}

impl Default for SessionServer {
    fn default() -> Self { Self { client: None, output: Default::default(), output_cursor: String::new(), poll: PollState::default(), sessions: SessionRegistry::default(), graphics: HashMap::new(), pending: HashMap::new(), pending_bytes: 0, staged_output: HashMap::new() } }
}

impl SessionServer {
    pub fn serve_forever(&mut self, listener: VsockListener) -> Result<(), VsockError> {
        loop { self.turn(Some(&listener), -1)?; }
    }

    #[cfg(test)]
    fn serve_connection(&mut self, connection: VsockConnection) -> Result<(), VsockError> {
        let mut client = Client::new(connection)?;
        // Fixtures already know their pre-created sessions. Production publishes
        // CREATE/LIST/ATTACH first, before sending any retained session output.
        client.known.extend(self.sessions.list().map(|s| s.id.clone()));
        self.client = Some(client);
        while self.client.is_some() { self.turn(None, -1)?; }
        Ok(())
    }

    fn turn(&mut self, listener: Option<&VsockListener>, timeout: i32) -> Result<(), VsockError> {
        self.service_client();
        self.drain_local_input();
        let mut poll = std::mem::take(&mut self.poll);
        let result = (|| {
            // Reuse descriptor storage and ID allocations; active sessions are
            // already ordered by the registry. No hot-loop sort or ID vector.
            let mut count = 0;
            for session in self.sessions.running() {
                if count == poll.ids.len() { poll.ids.push(session.id.clone()); }
                else { poll.ids[count].clone_from(&session.id); }
                count += 1;
            }
            poll.ids.truncate(count);
            poll.fds.clear();
            poll.push(listener.map_or(-1, VsockListener::raw_fd), nix::libc::POLLIN);
            let client = self.client.as_ref();
            let capacity = client.is_some_and(|c| c.connection.has_capacity());
            poll.push(client.map_or(-1, |c| c.connection.raw_fd()),
                (if capacity { nix::libc::POLLIN } else { 0 }) |
                (if client.is_some_and(|c| c.connection.wants_write()) { nix::libc::POLLOUT } else { 0 }));
            let mut buffered_output = false;
            for index in 0..poll.ids.len() {
                let id = &poll.ids[index];
                let writable = self.pending.get(id).and_then(|q| q.front())
                    .is_some_and(|input| input.request.is_none() || capacity);
                let output = self.sessions.output_fd(id).unwrap_or(-1);
                let exit = self.sessions.exit_fd(id).unwrap_or(-1);
                let buffered = self.has_buffered_output(id);
                buffered_output |= buffered;
                poll.push(output, (if buffered { 0 } else { nix::libc::POLLIN }) |
                    if writable { nix::libc::POLLOUT } else { 0 });
                poll.push(exit, nix::libc::POLLIN);
            }
            for fd in self.sessions.retired_fds() { poll.push(fd, nix::libc::POLLIN); }
            let immediate = buffered_output || client.is_some_and(|c|
                c.connection.has_pty_capacity() && self.output.tails.iter().any(|(id, tail)| tail.has_pending() && c.known.contains(id)));
            poll.wait(if immediate || (listener.is_none() && client.is_none() && timeout < 0) { 0 } else { timeout })?;
            if poll.readable(1) { self.service_client(); }
            if poll.readable(0) {
                match listener.unwrap().accept() {
                    Ok(connection) if self.client.is_none() => self.client = Some(Client::new(connection)?),
                    Ok(_) | Err(VsockError::UntrustedPeer) | Err(VsockError::Socket(nix::errno::Errno::EINTR)) => (),
                    Err(error) => return Err(error),
                }
            }
            for (index, id) in poll.ids.iter().enumerate() {
                let pty_index = 2 + index * 2;
                // POLLIN was omitted only when parser work was already queued.
                // Reuse that snapshot bit instead of rescanning every idle parser.
                let buffered = poll.fds[pty_index].events & nix::libc::POLLIN == 0;
                if buffered { self.service_client(); }
                let exited = poll.readable(pty_index + 1) && self.sessions.exit_fd(id) == Some(poll.fds[pty_index + 1].fd);
                if exited { self.finish_child(id); }
                if buffered || exited || poll.readable(pty_index) {
                    // Control may have terminated/resized this snapshot's session.
                    if self.has_buffered_output(id) || self.sessions.output_fd(id).ok() == Some(poll.fds[pty_index].fd) {
                        self.drain_output(id);
                    }
                    self.complete_exit(id);
                }
            }
            if poll.fds.iter().skip(2 + 2 * poll.ids.len()).any(|fd| fd.revents != 0) { self.sessions.reap_retired(); }
            Ok(())
        })();
        self.poll = poll;
        result
    }

    fn service_client(&mut self) {
        let Some(mut client) = self.client.take() else { return; };
        let result = (|| -> Result<(), VsockError> {
            client.connection.flush_output()?;
            if client.connection.has_capacity() {
                if let Some(frame) = client.connection.read_frame_ready()? {
                    let request = Self::decode_request(&frame);
                    match request {
                        Ok(SessionRequest::Write { session_id, bytes }) => {
                            if let Err(error) = self.queue_input(session_id, bytes, Some(frame.request_id)) {
                                client.connection.write_frame(&Self::error(frame.request_id, error.code, error.message))?;
                            }
                        }
                        request => {
                            let resize = match &request { Ok(SessionRequest::Resize { session_id, .. }) => Some(session_id.clone()), _ => None };
                            let response = self.handle(frame.request_id, request, &mut client.attached, &mut client.known);
                            if response.kind == FrameKind::Response {
                                if let Some(session_id) = resize {
                                    // Keep healthy output; discard only backlog that cannot
                                    // fit before the geometry boundary without blocking control.
                                    while self.output.has_pending(&session_id) && client.connection.has_pty_capacity() {
                                        self.flush_tail(&mut client.connection, &session_id)?;
                                    }
                                    let staged = self.staged_output.remove(&session_id).is_some();
                                    let partial = self.graphics.get_mut(&session_id).is_some_and(GraphicsTransport::discard_partial);
                                    let dropped = self.output.resize_boundary(&session_id);
                                    if let Some(dropped_bytes) = if staged || partial { Some(-1) } else { dropped } {
                                        Self::write_event(&mut client.connection, SessionEvent::OutputGap { session_id: &session_id, dropped_bytes })?;
                                    }
                                }
                            }
                            client.connection.write_frame(&response)?;
                        }
                    }
                }
            }
            self.drain_input(Some(&mut client.connection))?;
            // BTreeMap order + one reusable cursor gives fair turns without
            // copying/sorting all pending IDs on every output packet.
            for _ in 0..self.output.tails.len() {
                if !client.connection.has_pty_capacity() { break; }
                use std::ops::Bound::{Excluded, Unbounded};
                let next = self.output.tails.range::<str, _>((Excluded(self.output_cursor.as_str()), Unbounded))
                    .next().or_else(|| self.output.tails.first_key_value()).map(|(id, _)| id);
                let Some(next) = next else { break; };
                self.output_cursor.clone_from(next);
                if client.known.contains(&self.output_cursor) && self.output.has_pending(&self.output_cursor) {
                    let id = std::mem::take(&mut self.output_cursor);
                    let result = self.flush_tail(&mut client.connection, &id);
                    self.output_cursor = id;
                    result?;
                }
            }
            client.connection.flush_output()
        })();
        if let Err(error) = result {
            eprintln!("droid-sessiond: client disconnected: {error:?}");
            // Unknown WRITE results are never retried. Locally generated replies
            // still drain, and partially parsed guest output remains owned here.
            for queue in self.pending.values_mut() { queue.retain(|input| input.request.is_none()); }
            self.pending.retain(|_, queue| !queue.is_empty());
            self.pending_bytes = self.pending.values().flatten().map(|input| input.bytes.len()).sum();
            self.output.disconnected(client.known.into_iter().filter(|id| self.sessions.get(id).is_some()));
            if let Some(id) = client.attached { let _ = self.sessions.detach(&id); }
        } else { self.client = Some(client); }
    }

    fn flush_tail(&mut self, connection: &mut VsockConnection, id: &str) -> Result<(), VsockError> {
        if let Some(dropped_bytes) = self.output.tails.get_mut(id).unwrap().gap.take() {
            Self::write_event(connection, SessionEvent::OutputGap { session_id: id, dropped_bytes })?;
        }
        if let Some(bytes) = self.output.pop(id) {
            Self::write_event(connection, SessionEvent::Output { session_id: id, bytes: &bytes })?;
        }
        let tail = self.output.tails.get_mut(id).unwrap();
        if tail.chunks.is_empty() {
            if let Some(exit_code) = tail.exit.take() {
                Self::write_event(connection, SessionEvent::Exited { session_id: id, exit_code })?;
                self.output.remove(id);
            }
            // Keep empty live-session queue metadata for the next packet. Empty
            // tails are never considered runnable, so they cannot cause a spin.
        }
        Ok(())
    }

    fn drain_local_input(&mut self) {
        if self.client.is_none() { let _ = self.drain_input(None); }
    }

    fn queue_input(&mut self, id: String, bytes: Vec<u8>, request: Option<u64>) -> Result<(), DispatchError> {
        if self.sessions.output_fd(&id).is_err() {
            return Err(DispatchError::not_found("session input is unavailable"));
        }
        let own = self.pending.get(&id);
        if own.is_some_and(|q| q.len() >= 64 || bytes.len() > 1024 * 1024 - q.iter().map(|p| p.bytes.len()).sum::<usize>())
            || bytes.len() > 1024 * 1024 {
            return Err(DispatchError { code: ErrorCode::GuestError, message: "session input queue is full" });
        }
        let count: usize = self.pending.values().map(VecDeque::len).sum();
        if count >= 256 || bytes.len() > 4 * 1024 * 1024 - self.pending_bytes {
            return Err(DispatchError { code: ErrorCode::GuestError, message: "session input queue is full" });
        }
        self.pending_bytes += bytes.len();
        self.pending.entry(id).or_default().push_back(PendingInput { bytes, offset: 0, request });
        Ok(())
    }

    fn drain_input(&mut self, mut connection: Option<&mut VsockConnection>) -> Result<(), VsockError> {
        for (id, queue) in &mut self.pending {
            let mut budget = 32 * 1024;
            while let Some(input) = queue.front_mut() {
                if input.request.is_some() && connection.as_ref().is_some_and(|c| !c.has_capacity()) { break; }
                let end = input.bytes.len().min(input.offset + budget);
                let result = if input.offset == end { Ok(0) } else { self.sessions.write_input(id, &input.bytes[input.offset..end]) };
                let complete = match result {
                    Ok(count) => { input.offset += count; budget -= count; input.offset == input.bytes.len() },
                    Err(PtyError::Nix(nix::errno::Errno::EAGAIN | nix::errno::Errno::EINTR)) => false,
                    Err(_) => true,
                };
                if complete {
                    let input = queue.pop_front().unwrap();
                    self.pending_bytes -= input.bytes.len();
                    if let Some(request_id) = input.request {
                        let response = if input.offset == input.bytes.len() {
                            Frame { service: Service::Session, kind: FrameKind::Response, flags: 0, request_id,
                                payload: SessionResponse::Written { session_id: id.clone(), accepted_bytes: input.offset as i32 }
                                    .encode().map_err(|_| std::io::Error::other("unable to encode input result"))? }
                        } else { Self::error(request_id, ErrorCode::GuestError, "session input became unavailable") };
                        if let Some(connection) = connection.as_mut() { connection.write_frame(&response)?; }
                    }
                }
                if !complete || budget == 0 { break; }
            }
        }
        self.pending.retain(|_, queue| !queue.is_empty());
        Ok(())
    }

    fn decode_request(frame: &Frame) -> Result<SessionRequest, DispatchError> {
        if frame.service != Service::Session || frame.kind != FrameKind::Request {
            return Err(DispatchError { code: ErrorCode::Unsupported, message: "only session requests are accepted" });
        }
        SessionRequest::decode(&frame.payload).map_err(|_| DispatchError::invalid("invalid session request"))
    }

    fn handle(&mut self, request_id: u64, request: Result<SessionRequest, DispatchError>, attached: &mut Option<String>, known: &mut HashSet<String>) -> Frame {
        let request = match request { Ok(value) => value, Err(error) => return Self::error(request_id, error.code, error.message) };
        match self.dispatch(request, attached) {
            Ok(response) => match response.encode() {
                Ok(payload) => {
                    match &response {
                        SessionResponse::Created(s) | SessionResponse::Attached(s) => { known.insert(s.id.clone()); }
                        SessionResponse::Listed(sessions) => { known.extend(sessions.iter().map(|s| s.id.clone())); }
                        SessionResponse::Terminating(id) => { known.remove(id); }
                        _ => (),
                    }
                    Frame { service: Service::Session, kind: FrameKind::Response,
                        flags: 0, request_id, payload }
                }
                Err(_) => Self::error(request_id, ErrorCode::GuestError, "response exceeds protocol limits"),
            },
            Err(error) => Self::error(request_id, error.code, error.message),
        }
    }

    fn dispatch(
        &mut self,
        request: SessionRequest,
        attached: &mut Option<String>,
    ) -> Result<SessionResponse, DispatchError> {
        match request {
            SessionRequest::ResourceUsage => crate::resources::read().map(SessionResponse::Resources)
                .map_err(|_| DispatchError { code: ErrorCode::GuestError, message: "guest metrics unavailable" }),
            SessionRequest::Create { shell, cwd, env, rows, cols } => {
                let environment: Vec<_> = env.into_iter().collect();
                let id = self.sessions.create_shell(&shell, &cwd, rows, cols).id.clone();
                self.sessions.start_shell(&id, &environment).map_err(DispatchError::from_pty)?;
                self.graphics.insert(id.clone(), GraphicsTransport::default());
                Ok(SessionResponse::Created(self.descriptor(&id)?))
            }
            SessionRequest::List => Ok(SessionResponse::Listed(
                self.sessions.list().map(SessionDescriptor::from).collect(),
            )),
            SessionRequest::Attach { session_id } => {
                if attached.as_deref() != Some(session_id.as_str()) {
                    self.sessions.attach(&session_id).map_err(DispatchError::from_pty)?;
                    if let Some(previous) = attached.replace(session_id.clone()) {
                        let _ = self.sessions.detach(&previous);
                    }
                }
                Ok(SessionResponse::Attached(self.descriptor(&session_id)?))
            }
            SessionRequest::Detach { session_id } => {
                if attached.as_deref() != Some(session_id.as_str()) {
                    return Err(DispatchError::invalid("session is not attached to this client"));
                }
                self.sessions.detach(&session_id).map_err(DispatchError::from_pty)?;
                *attached = None;
                Ok(SessionResponse::Detached(session_id))
            }
            SessionRequest::Resize { session_id, rows, cols } => {
                self.sessions.resize(&session_id, rows, cols).map_err(DispatchError::from_pty)?;
                Ok(SessionResponse::Resized(self.descriptor(&session_id)?))
            }
            SessionRequest::Write { session_id, bytes } => {
                let accepted = self.sessions.write_input(&session_id, &bytes).map_err(DispatchError::from_pty)?;
                Ok(SessionResponse::Written { session_id, accepted_bytes: accepted as i32 })
            }
            SessionRequest::Terminate { session_id, signal } => {
                let signal = nix::sys::signal::Signal::try_from(signal)
                    .map_err(|_| DispatchError::invalid("invalid POSIX signal"))?;
                self.sessions.terminate(&session_id, signal).map_err(DispatchError::from_pty)?;
                self.output.remove(&session_id);
                // Keep pending requests until drain_input returns explicit
                // errors for this removed destination on the live connection.
                self.graphics.remove(&session_id);
                self.staged_output.remove(&session_id);
                if attached.as_deref() == Some(session_id.as_str()) {
                    *attached = None;
                }
                Ok(SessionResponse::Terminating(session_id))
            }
        }
    }

    fn descriptor(&self, id: &str) -> Result<SessionDescriptor, DispatchError> {
        self.sessions.get(id).map(SessionDescriptor::from)
            .ok_or_else(|| DispatchError::not_found("session not found"))
    }

    fn error(request_id: u64, code: ErrorCode, message: &str) -> Frame {
        Frame {
            service: Service::Session,
            kind: FrameKind::Error,
            flags: 0,
            request_id,
            payload: encode_error(code, message).expect("constant error is within limit"),
        }
    }

    fn drain_output(&mut self, id: &str) -> bool {
        match self.stream_output(id) {
            Ok(progress) => progress,
            Err(error) => {
                // Only a failed PTY read is a process/output failure. Input
                // congestion and dropped local replies never enter this branch.
                eprintln!("droid-sessiond: session {id} output failed: {error:?}");
                let _ = self.dispatch(SessionRequest::Terminate { session_id: id.into(), signal: 9 }, &mut None);
                self.output.tails.entry(id.into()).or_default().exit = Some(-1);
                false
            }
        }
    }

    fn has_buffered_output(&self, id: &str) -> bool {
        self.staged_output.contains_key(id) || self.graphics.get(id).is_some_and(GraphicsTransport::has_pending_media)
    }

    fn stream_output(&mut self, id: &str) -> Result<bool, VsockError> {
        if let Some(graphics) = self.graphics.get_mut(id).filter(|g| g.has_pending_media()) {
            let output = graphics.advance_media();
            self.write_graphics_output(id, output);
            return Ok(true);
        }
        let mut buffer = [0_u8; 8192];
        if let Some(bytes) = self.staged_output.remove(id) {
            self.process_output(id, &bytes);
            return Ok(true);
        }
        match self.sessions.read_output(id, &mut buffer) {
            Ok(size) if size > 0 => { self.process_output(id, &buffer[..size]); Ok(true) }
            Ok(_) | Err(PtyError::Nix(nix::errno::Errno::EIO)) => {
                self.finish_graphics(id);
                self.sessions.mark_output_eof(id);
                Ok(false)
            }
            Err(PtyError::Nix(nix::errno::Errno::EAGAIN | nix::errno::Errno::EINTR)) => Ok(false),
            Err(_) => Err(std::io::Error::other("unable to read PTY output").into()),
        }
    }

    fn finish_graphics(&mut self, id: &str) {
        if let Some(mut graphics) = self.graphics.remove(id) { self.write_graphics_output(id, graphics.finish()); }
    }

    fn finish_child(&mut self, id: &str) {
        if let Ok(Some(_)) = self.sessions.poll_exit(id) {
            // Reap and close the PTY now. Retain only its finite already-available
            // raw tail; accepted graphics finish in normal cooperative turns.
            let mut bytes = self.staged_output.remove(id).unwrap_or_default();
            let mut buffer = [0u8; 8192];
            let mut exhausted = true;
            for _ in 0..8 {
                match self.sessions.read_output(id, &mut buffer) {
                    Ok(count) if count > 0 => bytes.extend_from_slice(&buffer[..count]),
                    _ => { exhausted = false; break; }
                }
            }
            if exhausted && matches!(self.sessions.read_output(id, &mut [0u8; 1]), Ok(1)) {
                self.output.tails.entry(id.into()).or_default().gap = Some(-1);
            }
            if !bytes.is_empty() { self.staged_output.insert(id.into(), bytes); }
            self.sessions.close_pty(id);
            // The next input drain rejects WRITE requests explicitly. No PTY
            // or descendant remains an owner of the final graphics conversion.
        }
    }

    fn complete_exit(&mut self, id: &str) {
        if self.has_buffered_output(id) { return; }
        let code = match self.sessions.get(id).map(|s| &s.state) {
            Some(crate::session::SessionState::Exited(code)) => *code,
            _ => return,
        };
        self.finish_graphics(id);
        self.sessions.close_output(id);
        self.output.tails.entry(id.into()).or_default().exit = Some(code);
        if let Some(client) = self.client.as_mut() {
            if client.attached.as_deref() == Some(id) {
                let _ = self.sessions.detach(id); client.attached = None;
            }
        }
    }

    fn process_output(&mut self, id: &str, bytes: &[u8]) {
        if !self.graphics.contains_key(id) { self.graphics.insert(id.to_owned(), GraphicsTransport::default()); }
        let (consumed, output) = self.graphics.get_mut(id).unwrap().accept_prefix(bytes);
        if consumed < bytes.len() { self.staged_output.insert(id.to_owned(), bytes[consumed..].to_vec()); }
        self.write_graphics_output(id, output)
    }

    fn write_graphics_output(
        &mut self, session_id: &str, output: GraphicsOutput,
    ) {
        for reply in output.replies {
            // Best effort under bounded overload, like terminal-query replies on
            // the host. A full queue is not evidence that this PTY has failed.
            let _ = self.queue_input(session_id.to_owned(), reply, None);
        }
        for bytes in output.bytes {
            self.output.push(session_id, bytes);
        }
    }

    fn write_event(
        connection: &mut VsockConnection,
        event: SessionEvent<'_>,
    ) -> Result<(), VsockError> {
        let bytes = event.encode_frame().map_err(|_| std::io::Error::other("session event exceeds protocol limits"))?;
        connection.write_encoded(bytes)
    }
}

struct PendingInput { bytes: Vec<u8>, offset: usize, request: Option<u64> }
struct Client { connection: VsockConnection, attached: Option<String>, known: HashSet<String> }
impl Client {
    fn new(mut connection: VsockConnection) -> Result<Self, VsockError> {
        connection.enable_nonblocking()?;
        Ok(Self { connection, attached: None, known: HashSet::new() })
    }
}
#[derive(Default)]
struct PollState { fds: Vec<nix::libc::pollfd>, ids: Vec<String> }
impl PollState {
    fn push(&mut self, fd: std::os::fd::RawFd, events: i16) {
        self.fds.push(nix::libc::pollfd { fd, events, revents: 0 });
    }
    fn readable(&self, index: usize) -> bool {
        self.fds[index].revents & (nix::libc::POLLIN | nix::libc::POLLHUP | nix::libc::POLLERR) != 0
    }
    fn wait(&mut self, timeout: i32) -> Result<(), VsockError> {
        loop {
            let result = unsafe { nix::libc::poll(self.fds.as_mut_ptr(), self.fds.len() as _, timeout) };
            if result >= 0 { return Ok(()); }
            if nix::errno::Errno::last() != nix::errno::Errno::EINTR { return Err(nix::errno::Errno::last().into()); }
        }
    }
}

#[derive(Debug)]
struct DispatchError { code: ErrorCode, message: &'static str }

impl DispatchError {
    fn invalid(message: &'static str) -> Self { Self { code: ErrorCode::InvalidArgument, message } }
    fn not_found(message: &'static str) -> Self { Self { code: ErrorCode::NotFound, message } }
    fn from_pty(error: PtyError) -> Self {
        match error {
            PtyError::SessionNotFound => Self::not_found("session not found"),
            PtyError::SessionNotStarted => Self { code: ErrorCode::GuestError, message: "session is not running" },
            PtyError::InvalidCommand => Self::invalid("invalid shell command or working directory"),
            PtyError::InvalidEnvironment => Self::invalid("invalid PTY configuration"),
            PtyError::Nix(_) => Self { code: ErrorCode::GuestError, message: "guest PTY operation failed" },
        }
    }
}

#[cfg(test)]
mod multiplex_tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::net::UnixStream;

    #[test]
    fn full_input_queues_never_kill_a_sibling_generating_graphics_replies() {
        let mut server = SessionServer::default();
        let mut ids = Vec::new();
        for _ in 0..4 {
            let id = script(&mut server, "stty -echo -icanon; exec sleep 10");
            server.queue_input(id.clone(), vec![b'x'; 512 * 1024], Some(1)).unwrap();
            for _ in 0..63 { server.queue_input(id.clone(), b"reply".to_vec(), None).unwrap(); }
            assert!(server.queue_input(id.clone(), b"overflow".to_vec(), Some(2)).is_err());
            ids.push(id);
        }
        let healthy = script(&mut server, r#"printf '\033_Gi=7,t=f;L25vLXN1Y2gtbWVkaWE=\033\\'; printf SURVIVED; exec sleep 10"#);
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while !server.output.tails.get(&healthy).is_some_and(|tail| tail.chunks.back().is_some_and(|(_, b)| b.ends_with(b"SURVIVED"))) {
            assert!(std::time::Instant::now() < deadline);
            server.turn(None, 10).unwrap();
        }
        assert_eq!(256, server.pending.values().map(VecDeque::len).sum::<usize>());
        assert!(!server.pending.contains_key(&healthy), "global overload drops only the generated reply");
        assert_eq!(crate::session::SessionState::Running, server.sessions.get(&healthy).unwrap().state);
        assert!(server.sessions.output_fd(&healthy).is_ok());
        // Free one other session: the sibling can now enqueue despite the first
        // session still being at its own packet cap.
        server.dispatch(SessionRequest::Terminate { session_id: ids.pop().unwrap(), signal: 9 }, &mut None).unwrap();
        server.drain_local_input();
        server.process_output(&healthy, b"\x1b_Gi=7,t=f;L25vLXN1Y2gtbWVkaWE=\x1b\\");
        assert_eq!(1, server.pending[&healthy].len());
        ids.push(healthy);
        for id in ids { server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL).unwrap(); }
        while server.sessions.retired_fds().next().is_some() {
            assert!(std::time::Instant::now() < deadline);
            server.turn(None, 10).unwrap();
        }
    }

    #[test]
    fn leader_exit_is_reaped_even_while_a_descendant_holds_the_slave() {
        let mut server = SessionServer::default();
        let marker = std::env::temp_dir().join(format!("term-pidfd-test-{}", std::process::id()));
        let id = script(&mut server, &format!("trap '' HUP; sleep 5 & printf '%s %s' \"$$\" \"$!\" > '{}'; printf FINAL; exit 7", marker.display()));
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while !matches!(server.sessions.get(&id).unwrap().state, crate::session::SessionState::Exited(7)) {
            assert!(std::time::Instant::now() < deadline);
            server.turn(None, 10).unwrap();
        }
        let pids = std::fs::read_to_string(&marker).unwrap();
        let pids: Vec<i32> = pids.split_whitespace().map(|p| p.parse().unwrap()).collect();
        let leader_exists = std::path::Path::new(&format!("/proc/{}/stat", pids[0])).exists();
        let descendant_alive = nix::sys::signal::kill(nix::unistd::Pid::from_raw(pids[1]), None).is_ok();
        let _ = nix::sys::signal::kill(nix::unistd::Pid::from_raw(pids[1]), nix::sys::signal::Signal::SIGKILL);
        std::fs::remove_file(marker).unwrap();
        assert!(descendant_alive, "the inherited slave holder outlives the leader");
        assert!(!leader_exists, "leader must already have been reaped, not left as a zombie");
        assert!(server.sessions.output_fd(&id).is_err());
        assert!(server.sessions.exit_fd(&id).is_none());
        assert_eq!(0, server.sessions.running().count());
        let tail = &server.output.tails[&id];
        assert_eq!(Some(7), tail.exit);
        assert!(tail.chunks.back().unwrap().1.ends_with(b"FINAL"));
    }

    #[test]
    fn reaped_child_finishes_accepted_graphics_in_cooperative_turns() {
        let mut server = SessionServer::default();
        let file = std::env::temp_dir().join(format!("term-final-drain-{}", std::process::id()));
        std::fs::write(&file, vec![7; 64 * 1024]).unwrap();
        let name = crate::graphics_transport::encode_base64(file.to_str().unwrap().as_bytes());
        let command = format!("\x1b_Gi=1,t=f;{name}\x1b\\");
        let id = script(&mut server, "exit 0");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while server.sessions.poll_exit(&id).unwrap().is_none() {
            assert!(std::time::Instant::now() < deadline);
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        // Reaping/PTY closure does not wait for ten accepted image conversions;
        // their final output continues through the same bounded loop.
        server.staged_output.insert(id.clone(), command.repeat(10).into_bytes());
        server.finish_child(&id);
        assert!(server.sessions.output_fd(&id).is_err());
        assert!(server.sessions.exit_fd(&id).is_none());
        let mut turns = 0;
        while server.sessions.running().next().is_some() {
            assert!(std::time::Instant::now() < deadline);
            server.turn(None, 0).unwrap(); turns += 1;
        }
        std::fs::remove_file(file).unwrap();
        assert!(turns > 10);
        assert!(!server.has_buffered_output(&id));
        assert_eq!(server.output.tails[&id].gap, None);
        assert_eq!(server.output.tails[&id].exit, Some(0));
        let final_output = server.output.tails[&id].chunks.iter().flat_map(|(_, b)| b.iter().copied()).collect::<Vec<_>>();
        assert_eq!(final_output.windows(4).filter(|b| *b == b"m=0;").count(), 10);
    }

    #[test]
    fn session_end_rejects_every_pending_write_without_waiting_for_host_traffic() {
        for explicit in [false, true] {
            let mut server = SessionServer::default();
            let id = script(&mut server, "stty -echo -icanon; printf 'READY:%s' \"$$\"; exec sleep 10");
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
            while !server.output.tails.contains_key(&id) {
                assert!(std::time::Instant::now() < deadline);
                server.turn(None, 10).unwrap();
            }
            // Wait for raw mode before queueing: canonical mode may accept and
            // discard huge unterminated lines even though no application reads.
            let ready = server.output.tails[&id].chunks.iter().flat_map(|(_, b)| b.iter().copied()).collect::<Vec<_>>();
            let pid = std::str::from_utf8(&ready).unwrap().strip_prefix("READY:").unwrap().parse().unwrap();
            for request in 1..=4 { server.queue_input(id.clone(), vec![b'x'; 512 * 1024 / 4], Some(request)).unwrap(); }
            let (mut host, worker) = connected(server);
            if explicit {
                let terminate = Frame { service: Service::Session, kind: FrameKind::Request, flags: 0, request_id: 99,
                    payload: SessionRequest::Terminate { session_id: id, signal: 9 }.encode().unwrap() };
                crate::vsock::write_frame_to(&mut host, &terminate).unwrap();
            } else {
                nix::sys::signal::kill(nix::unistd::Pid::from_raw(pid), nix::sys::signal::Signal::SIGKILL).unwrap();
            }
            let mut rejected = Vec::new();
            let mut ended = false;
            while rejected.len() < 4 || !ended {
                let frame = crate::vsock::read_frame_from(&mut host).unwrap_or_else(|error|
                    panic!("explicit={explicit}, rejected={rejected:?}, ended={ended}, read={error:?}"));
                if frame.kind == FrameKind::Error { rejected.push(frame.request_id); }
                else if explicit { ended |= frame.kind == FrameKind::Response && frame.request_id == 99; }
                else { ended |= frame.kind == FrameKind::Stream && frame.payload[0] == 0x91; }
            }
            assert_eq!(rejected, vec![1, 2, 3, 4]);
            drop(host); worker.join().unwrap();
        }
    }

    #[test]
    fn exec_does_not_inherit_another_sessions_pty_master() {
        let mut server = SessionServer::default();
        let first = script(&mut server, "exec sleep 5");
        let second = script(&mut server, "for f in /proc/$$/fd/*; do readlink \"$f\"; done");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while !matches!(server.sessions.get(&second).unwrap().state, crate::session::SessionState::Exited(_)) {
            assert!(std::time::Instant::now() < deadline);
            server.turn(None, 10).unwrap();
        }
        let output = server.output.tails[&second].chunks.iter().flat_map(|(_, b)| b.iter().copied()).collect::<Vec<_>>();
        assert!(output.windows(b"/dev/pts/".len()).any(|w| w == b"/dev/pts/"));
        assert!(!output.windows(b"ptmx".len()).any(|w| w == b"ptmx"), "{}", String::from_utf8_lossy(&output));
        server.sessions.terminate(&first, nix::sys::signal::Signal::SIGKILL).unwrap();
        while server.sessions.retired_fds().next().is_some() { server.turn(None, 10).unwrap(); }
    }

    #[test]
    fn offline_and_unread_client_never_backpressure_guest_processes() {
        for slow_client in [false, true] {
            let mut server = SessionServer::default();
            let id = script(&mut server, "head -c 33554432 /dev/zero; printf FINISHED; exit 7");
            let peer = if slow_client {
                let (host, guest) = UnixStream::pair().unwrap();
                let connection = VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(guest))).unwrap();
                let mut client = Client::new(connection).unwrap();
                client.known.insert(id.clone()); server.client = Some(client);
                Some(host)
            } else { None };
            let started = std::time::Instant::now();
            while !server.output.tails.get(&id).is_some_and(|tail| tail.exit == Some(7)) {
                assert!(started.elapsed() < std::time::Duration::from_secs(5), "PTY stalled with slow_client={slow_client}");
                server.turn(None, 10).unwrap();
            }
            eprintln!("32 MiB PTY completed: slow_client={slow_client}, elapsed={:?}", started.elapsed());
            let tail = &server.output.tails[&id];
            assert!(tail.gap.unwrap() > 0);
            assert_eq!(tail.exit, Some(7));
            assert!(tail.chunks.back().unwrap().1.ends_with(b"FINISHED"));
            assert!(server.sessions.output_fd(&id).is_err(), "exit must release the PTY while offline");
            drop(peer);
        }
    }

    #[test]
    fn reconnect_identifies_sessions_before_gap_tail_and_exit_without_replaying_input() {
        let mut server = SessionServer::default();
        let id = script(&mut server, "head -c 16777216 /dev/zero; printf TAIL; exit 9");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while !server.output.tails.get(&id).is_some_and(|tail| tail.exit == Some(9)) {
            assert!(std::time::Instant::now() < deadline);
            server.turn(None, 10).unwrap();
        }
        let (mut host, guest) = UnixStream::pair().unwrap();
        host.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
        server.client = Some(Client::new(VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(guest))).unwrap()).unwrap());
        server.service_client();
        assert!(server.client.as_ref().unwrap().known.is_empty());
        assert!(!server.client.as_ref().unwrap().connection.wants_write());
        let request = Frame { service: Service::Session, kind: FrameKind::Request, flags: 0, request_id: 1,
            payload: SessionRequest::List.encode().unwrap() };
        crate::vsock::write_frame_to(&mut host, &request).unwrap();
        let reader = std::thread::spawn(move || {
            let first = crate::vsock::read_frame_from(&mut host).unwrap();
            assert_eq!((FrameKind::Response, 1), (first.kind, first.request_id));
            let gap = crate::vsock::read_frame_from(&mut host).unwrap();
            assert_eq!(gap.payload[0], 0x92);
            let mut output = Vec::new();
            loop {
                let frame = crate::vsock::read_frame_from(&mut host).unwrap();
                if frame.payload[0] == 0x91 {
                    assert_eq!(frame.payload[frame.payload.len()-4..], 9i32.to_be_bytes());
                    break;
                }
                assert_eq!(frame.payload[0], 0x90);
                let length = u16::from_be_bytes(frame.payload[1..3].try_into().unwrap()) as usize;
                output.extend_from_slice(&frame.payload[7 + length..]);
            }
            assert!(output.ends_with(b"TAIL"));
            assert!(output.len() <= 9 * 1024 * 1024);
        });
        while !reader.is_finished() {
            assert!(std::time::Instant::now() < deadline);
            server.turn(None, 10).unwrap();
        }
        reader.join().unwrap();
    }

    #[test]
    fn disconnect_drops_uncertain_user_input_but_retains_local_replies_and_parser_tail() {
        let mut server = SessionServer::default();
        let id = script(&mut server, "exec sleep 30");
        server.queue_input(id.clone(), vec![1; 500], Some(42)).unwrap();
        server.queue_input(id.clone(), b"local".to_vec(), None).unwrap();
        server.staged_output.insert(id.clone(), b"partial graphics".to_vec());
        let (host, guest) = UnixStream::pair().unwrap();
        let mut client = Client::new(VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(guest))).unwrap()).unwrap();
        client.known.insert(id.clone()); server.client = Some(client);
        drop(host); server.service_client();
        assert!(server.client.is_none());
        assert_eq!(server.pending_bytes, 5);
        assert!(server.pending[&id].iter().all(|input| input.request.is_none()));
        assert_eq!(server.output.tails[&id].gap, Some(-1));
        assert_eq!(server.staged_output[&id], b"partial graphics");
        server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL).unwrap();
    }

    fn client_pair(server: &mut SessionServer, ids: &[&str]) -> UnixStream {
        let (host, peer) = UnixStream::pair().unwrap();
        host.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
        let mut client = Client::new(VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(peer))).unwrap()).unwrap();
        client.known.extend(ids.iter().map(|id| (*id).to_owned()));
        server.client = Some(client); host
    }

    #[test]
    fn resize_discards_pending_media_and_old_raw_text_before_response() {
        let mut server = SessionServer::default();
        let id = script(&mut server, "exec sleep 10");
        let mut host = client_pair(&mut server, &[&id]);
        let file = std::env::temp_dir().join(format!("tty-graphics-protocol-resize-{}", std::process::id()));
        std::fs::write(&file, vec![7; 65536]).unwrap();
        let name = crate::graphics_transport::encode_base64(file.to_str().unwrap().as_bytes());
        server.process_output(&id, format!("\x1b_Gi=1,t=t;{name}\x1b\\OLD_GEOMETRY_TEXT").as_bytes());
        server.drain_output(&id); // A multipart image has already started.
        assert!(server.graphics[&id].has_pending_media());
        crate::vsock::write_frame_to(&mut host, &Frame { service: Service::Session, kind: FrameKind::Request,
            flags: 0, request_id: 81, payload: SessionRequest::Resize { session_id: id.clone(), rows: 40, cols: 120 }.encode().unwrap() }).unwrap();
        server.service_client();
        let mut gap = false;
        loop {
            let frame = crate::vsock::read_frame_from(&mut host).unwrap();
            if frame.kind == FrameKind::Response { assert_eq!(frame.request_id, 81); break; }
            gap |= frame.payload[0] == 0x92;
            assert!(!frame.payload.windows(b"OLD_GEOMETRY_TEXT".len()).any(|b| b == b"OLD_GEOMETRY_TEXT"));
        }
        assert!(gap); assert!(!server.has_buffered_output(&id));
        assert!(file.exists(), "resize must not unlink a cancelled source");
        server.turn(None, 0).unwrap(); server.service_client();
        assert!(!server.output.has_pending(&id));
        server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL).unwrap();
        while server.sessions.retired_fds().next().is_some() { server.turn(None, 10).unwrap(); }
        std::fs::remove_file(file).unwrap();
    }

    #[test]
    fn media_conversion_yields_to_sibling_output_queries_and_termination() {
        let mut server = SessionServer::default();
        let image = script(&mut server, "exec sleep 10");
        let sibling = script(&mut server, "exec sleep 10");
        let mut host = client_pair(&mut server, &[&image, &sibling]);
        let file = std::env::temp_dir().join(format!("tty-graphics-protocol-yield-{}", std::process::id()));
        std::fs::write(&file, vec![7; 6 * 1024 * 1024]).unwrap();
        let name = crate::graphics_transport::encode_base64(file.to_str().unwrap().as_bytes());
        server.process_output(&image, format!("\x1b_Gi=1,t=t;{name}\x1b\\after").as_bytes());
        server.process_output(&sibling, b"SIBLING_PROGRESS");
        let mut sibling_progress = false;
        for request in 1..=2 {
            crate::vsock::write_frame_to(&mut host, &Frame { service: Service::Session, kind: FrameKind::Request,
                flags: 0, request_id: request, payload: SessionRequest::ResourceUsage.encode().unwrap() }).unwrap();
            server.turn(None, 0).unwrap();
            assert!(server.graphics[&image].has_pending_media(), "a query must not wait for the whole image");
            loop {
                let frame = crate::vsock::read_frame_from(&mut host).unwrap();
                if frame.request_id == request { assert_eq!(frame.kind, FrameKind::Response); break; }
                sibling_progress |= frame.payload.ends_with(b"SIBLING_PROGRESS");
            }
        }
        assert!(sibling_progress);
        crate::vsock::write_frame_to(&mut host, &Frame { service: Service::Session, kind: FrameKind::Request,
            flags: 0, request_id: 3, payload: SessionRequest::Terminate { session_id: image.clone(), signal: 9 }.encode().unwrap() }).unwrap();
        server.turn(None, 0).unwrap();
        loop { let frame = crate::vsock::read_frame_from(&mut host).unwrap(); if frame.request_id == 3 { assert_eq!(frame.kind, FrameKind::Response); break; } }
        assert!(server.sessions.get(&image).is_none()); assert!(!server.has_buffered_output(&image));
        assert!(file.exists());
        server.sessions.terminate(&sibling, nix::sys::signal::Signal::SIGKILL).unwrap();
        while server.sessions.retired_fds().next().is_some() { server.turn(None, 10).unwrap(); }
        std::fs::remove_file(file).unwrap();
    }

    #[test]
    fn reused_empty_tail_does_not_busy_poll_an_idle_connected_session() {
        let mut server = SessionServer::default();
        let id = script(&mut server, "exec sleep 10");
        let mut host = client_pair(&mut server, &[&id]);
        server.process_output(&id, b"ready"); server.service_client();
        crate::vsock::read_frame_from(&mut host).unwrap();
        assert!(server.output.tails.contains_key(&id)); assert!(!server.output.has_pending(&id));
        let started = std::time::Instant::now();
        server.turn(None, 60).unwrap();
        assert!(started.elapsed() >= std::time::Duration::from_millis(40), "empty queue forced an immediate poll");
        server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL).unwrap();
        while server.sessions.retired_fds().next().is_some() { server.turn(None, 10).unwrap(); }
    }

    #[test]
    fn resize_keeps_healthy_output_and_sheds_only_congestion_before_its_response() {
        for size in [12, 512 * 1024] {
            let mut server = SessionServer::default();
            let id = script(&mut server, "exec sleep 30");
            for chunk in vec![b'x'; size].chunks(32 * 1024) { server.output.push(&id, chunk.to_vec()); }
            let (mut host, guest) = UnixStream::pair().unwrap();
            host.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
            let mut client = Client::new(VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(guest))).unwrap()).unwrap();
            client.known.insert(id.clone()); server.client = Some(client);
            crate::vsock::write_frame_to(&mut host, &Frame { service: Service::Session,
                kind: FrameKind::Request, flags: 0, request_id: 12,
                payload: SessionRequest::Resize { session_id: id.clone(), rows: 40, cols: 100 }.encode().unwrap() }).unwrap();
            let reader = std::thread::spawn(move || {
                let mut delivered = 0; let mut dropped = 0;
                loop {
                    let frame = crate::vsock::read_frame_from(&mut host).unwrap();
                    if frame.kind == FrameKind::Response { assert_eq!(frame.request_id, 12); break; }
                    if frame.payload[0] == 0x92 {
                        dropped += i64::from_be_bytes(frame.payload[frame.payload.len()-8..].try_into().unwrap()) as usize;
                    } else {
                        assert_eq!(frame.payload[0], 0x90);
                        let id_length = u16::from_be_bytes(frame.payload[1..3].try_into().unwrap()) as usize;
                        delivered += frame.payload.len() - 7 - id_length;
                    }
                }
                assert_eq!(size, delivered + dropped);
                if size == 12 { assert_eq!(dropped, 0); } else { assert!(dropped > 0); }
            });
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
            while !reader.is_finished() { assert!(std::time::Instant::now() < deadline); server.turn(None, 10).unwrap(); }
            reader.join().unwrap();
            assert!(!server.output.tails.contains_key(&id) || server.output.tails[&id].chunks.is_empty());
            server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL).unwrap();
        }
    }

    fn script(server: &mut SessionServer, value: &str) -> String {
        let id = server.sessions.create_shell("/bin/sh", "/", 24, 80).id.clone();
        let args = ["/bin/sh", "-c", value];
        let encoded = format!("3:{}", args.iter().map(|arg| format!("{}:{}", arg.len(), arg)).collect::<String>());
        server.sessions.start_shell(&id, &[("DROID_SESSION_ARGV".into(), encoded)]).unwrap();
        id
    }
    fn connected(mut server: SessionServer) -> (UnixStream, std::thread::JoinHandle<()>) {
        let (host, peer) = UnixStream::pair().unwrap();
        host.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
        host.set_write_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
        let worker = std::thread::spawn(move || {
            let connection = VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(peer))).unwrap();
            let _ = server.serve_connection(connection);
            let ids: Vec<_> = server.sessions.list().map(|session| session.id.clone()).collect();
            for id in ids { let _ = server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL); }
        });
        (host, worker)
    }
    fn wait_text(host: &mut UnixStream, needle: &[u8]) {
        loop {
            let frame = crate::vsock::read_frame_from(host).unwrap();
            if frame.kind == FrameKind::Stream && frame.payload.windows(needle.len()).any(|w| w == needle) { break; }
        }
    }
    #[test]
    fn closed_pty_with_live_child_does_not_disconnect_or_block_termination() {
        let mut server = SessionServer::default();
        let closed = script(&mut server, "exec </dev/null >/dev/null 2>&1; exec sleep 30");
        let healthy = script(&mut server, "printf HEALTHY; exec sleep 30");
        let (mut host, worker) = connected(server);
        wait_text(&mut host, b"HEALTHY");
        std::thread::sleep(std::time::Duration::from_millis(150));
        let terminate = Frame { service: Service::Session, kind: FrameKind::Request, flags: 0, request_id: 90,
            payload: SessionRequest::Terminate { session_id: closed, signal: 9 }.encode().unwrap() };
        crate::vsock::write_frame_to(&mut host, &terminate).unwrap();
        let response = crate::vsock::read_frame_from(&mut host).unwrap();
        assert_eq!((90, FrameKind::Response), (response.request_id, response.kind));
        let list = Frame { service: Service::Session, kind: FrameKind::Request, flags: 0, request_id: 91,
            payload: SessionRequest::List.encode().unwrap() };
        crate::vsock::write_frame_to(&mut host, &list).unwrap();
        let response = crate::vsock::read_frame_from(&mut host).unwrap();
        assert_eq!((91, FrameKind::Response), (response.request_id, response.kind));
        assert!(response.payload.windows(healthy.len()).any(|bytes| bytes == healthy.as_bytes()));
        drop(host); worker.join().unwrap();
    }

    #[test]
    fn delayed_child_exit_after_pty_eof_is_reported_without_another_host_request() {
        let mut server = SessionServer::default();
        let id = script(&mut server, "exec </dev/null >/dev/null 2>&1; sleep 0.2; exit 7");
        let (mut host, worker) = connected(server);
        let frame = crate::vsock::read_frame_from(&mut host).unwrap();
        assert_eq!(FrameKind::Stream, frame.kind);
        assert!(frame.payload.windows(id.len()).any(|bytes| bytes == id.as_bytes()));
        assert_eq!(7i32.to_be_bytes(), frame.payload[frame.payload.len() - 4..]);
        drop(host); worker.join().unwrap();
    }

    #[test]
    fn partial_host_frame_does_not_pause_live_pty_output() {
        let mut server = SessionServer::default();
        script(&mut server, "stty -echo -icanon; printf READY; sleep 0.2; printf PARTIAL_PROGRESS; exec sleep 30");
        let (mut host, worker) = connected(server);
        wait_text(&mut host, b"READY");
        host.write_all(&[0x44, 0x50, 0x54]).unwrap();
        wait_text(&mut host, b"PARTIAL_PROGRESS");
        drop(host); worker.join().unwrap();
    }
    #[test]
    fn blocked_pty_input_leaves_sibling_output_and_resource_requests_available() {
        let mut server = SessionServer::default();
        let blocked = script(&mut server, "stty -echo -icanon; printf BLOCKED_READY; exec sleep 30");
        script(&mut server, "stty -echo -icanon; printf OTHER_READY; sleep 0.3; printf OTHER_PROGRESS; exec sleep 30");
        let (mut host, worker) = connected(server);
        // Either prompt may arrive first; both are unique and a query round trip
        // ensures the daemon has started before filling the unread PTY input.
        wait_text(&mut host, b"BLOCKED_READY");
        let input = Frame { service: Service::Session, kind: FrameKind::Request, flags: 0, request_id: 50,
            payload: SessionRequest::Write { session_id: blocked, bytes: vec![b'x'; 512 * 1024] }.encode().unwrap() };
        crate::vsock::write_frame_to(&mut host, &input).unwrap();
        let query = Frame { service: Service::Session, kind: FrameKind::Request, flags: 0, request_id: 51,
            payload: SessionRequest::ResourceUsage.encode().unwrap() };
        crate::vsock::write_frame_to(&mut host, &query).unwrap();
        let mut output = false; let mut resources = false;
        while !output || !resources {
            let frame = crate::vsock::read_frame_from(&mut host).unwrap();
            assert_ne!(frame.request_id, 50, "blocked input cannot have completed");
            output |= frame.kind == FrameKind::Stream && frame.payload.windows(14).any(|w| w == b"OTHER_PROGRESS");
            resources |= frame.request_id == 51 && frame.kind == FrameKind::Response && frame.payload.first() == Some(&0x88);
        }
        drop(host); worker.join().unwrap();
    }

}
