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
    output_cursor: usize,
    sessions: SessionRegistry,
    graphics: HashMap<String, GraphicsTransport>,
    pending: HashMap<String, VecDeque<PendingInput>>,
    pending_bytes: usize,
    staged_output: HashMap<String, Vec<u8>>,
    closed_outputs: HashSet<String>,
}

impl Default for SessionServer {
    fn default() -> Self { Self { client: None, output: Default::default(), output_cursor: 0, sessions: SessionRegistry::default(), graphics: HashMap::new(), pending: HashMap::new(), pending_bytes: 0, staged_output: HashMap::new(), closed_outputs: HashSet::new() } }
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
        self.reap_closed_outputs();
        for id in self.staged_output.keys().cloned().collect::<Vec<_>>() {
            self.drain_output(&id);
        }
        let outputs: Vec<_> = self.sessions.running()
            .filter(|s| !self.closed_outputs.contains(&s.id))
            .filter_map(|s| self.sessions.output_fd(&s.id).ok().map(|fd| (s.id.clone(), fd)))
            .collect();
        let descriptors: Vec<_> = outputs.iter().map(|(_, fd)| *fd).collect();
        let writable: Vec<_> = outputs.iter().map(|(id, _)| self.pending.get(id).and_then(|q| q.front())
            .is_some_and(|input| input.request.is_none() || self.client.as_ref().is_some_and(|c| c.connection.has_capacity()))).collect();
        let client = self.client.as_ref();
        let ready = poll_runtime(listener.map_or(-1, VsockListener::raw_fd),
            client.map_or(-1, |c| c.connection.raw_fd()), &descriptors,
            client.is_some_and(|c| c.connection.has_capacity()),
            client.is_some_and(|c| c.connection.wants_write()), &writable,
            if !self.staged_output.is_empty() || client.is_some_and(|c| c.connection.has_pty_capacity() &&
                self.output.tails.keys().any(|id| c.known.contains(id))) || (listener.is_none() && client.is_none() && timeout == -1) { 0 }
            else if !self.closed_outputs.is_empty() { if timeout < 0 { 100 } else { timeout.min(100) } }
            else { timeout })?;
        if ready.request_ready { self.service_client(); }
        if ready.listener_ready {
            match listener.unwrap().accept() {
                Ok(connection) if self.client.is_none() => self.client = Some(Client::new(connection)?),
                // A second client cannot steal process ownership or inject input.
                Ok(_) | Err(VsockError::UntrustedPeer) => (),
                Err(error) => return Err(error),
            }
        }
        // Dispatch control before the next output turn. A terminated descriptor
        // may still occur in this poll snapshot, so recheck ownership below.
        for index in ready.outputs {
            let id = &outputs[index].0;
            if self.sessions.output_fd(id).is_ok() { self.drain_output(id); }
        }
        Ok(())
    }

    fn service_client(&mut self) {
        let Some(mut client) = self.client.take() else { return; };
        let result = (|| -> Result<(), VsockError> {
            client.connection.flush_output()?;
            if client.connection.has_capacity() {
                if let Some(frame) = client.connection.read_frame_ready()? {
                    let input = if frame.service == Service::Session && frame.kind == FrameKind::Request {
                        SessionRequest::decode(&frame.payload).ok()
                    } else { None };
                    match input {
                        Some(SessionRequest::Write { session_id, bytes }) => {
                            if let Err(error) = self.queue_input(session_id, bytes, Some(frame.request_id)) {
                                client.connection.write_frame(&self.error(frame.request_id, error.code, error.message))?;
                            }
                        }
                        request => {
                            let response = self.handle(frame, &mut client.attached, &mut client.known);
                            if response.kind == FrameKind::Response {
                                if let Some(SessionRequest::Resize { session_id, .. }) = request {
                                    // Keep healthy output; discard only backlog that cannot
                                    // fit before the geometry boundary without blocking control.
                                    while self.output.tails.contains_key(&session_id) && client.connection.has_pty_capacity() {
                                        self.flush_tail(&mut client.connection, &session_id)?;
                                    }
                                    if let Some(dropped_bytes) = self.output.resize_boundary(&session_id) {
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
            // Round robin across sessions, including when one session floods.
            let mut ids = self.output.tails.keys().cloned().collect::<Vec<_>>();
            ids.sort_unstable();
            if !ids.is_empty() {
                let start = self.output_cursor % ids.len();
                ids.rotate_left(start);
                self.output_cursor = (start + 1) % ids.len();
            }
            for id in ids {
                if !client.known.contains(&id) || !client.connection.has_pty_capacity() { continue; }
                self.flush_tail(&mut client.connection, &id)?;
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
            }
            self.output.remove(id);
        }
        Ok(())
    }

    fn drain_local_input(&mut self) {
        if self.client.is_none() { let _ = self.drain_input(None); }
    }

    fn queue_input(&mut self, id: String, bytes: Vec<u8>, request: Option<u64>) -> Result<(), DispatchError> {
        if self.sessions.output_fd(&id).is_err() || self.closed_outputs.contains(&id) {
            return Err(DispatchError::not_found("session input is unavailable"));
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
        let ids: Vec<_> = self.pending.keys().cloned().collect();
        for id in ids {
            let queue = self.pending.get_mut(&id).unwrap();
            let input = queue.front_mut().unwrap();
            if input.request.is_some() && connection.as_ref().is_some_and(|c| !c.has_capacity()) { continue; }
            let end = input.bytes.len().min(input.offset + 32 * 1024);
            let result = if input.offset == end { Ok(0) } else { self.sessions.write_input(&id, &input.bytes[input.offset..end]) };
            let complete = match result {
                Ok(count) => { input.offset += count; input.offset == input.bytes.len() },
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
                    } else { self.error(request_id, ErrorCode::GuestError, "session input became unavailable") };
                    if let Some(connection) = connection.as_mut() { connection.write_frame(&response)?; }
                }
            }
            if self.pending.get(&id).is_some_and(VecDeque::is_empty) { self.pending.remove(&id); }
        }
        Ok(())
    }

    fn handle(&mut self, frame: Frame, attached: &mut Option<String>, known: &mut HashSet<String>) -> Frame {
        if frame.service != Service::Session || frame.kind != FrameKind::Request {
            return self.error(frame.request_id, ErrorCode::Unsupported, "only session requests are accepted");
        }
        let request = match SessionRequest::decode(&frame.payload) {
            Ok(request) => request,
            Err(_) => return self.error(frame.request_id, ErrorCode::InvalidArgument, "invalid session request"),
        };
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
                        flags: 0, request_id: frame.request_id, payload }
                }
                Err(_) => self.error(frame.request_id, ErrorCode::GuestError, "response exceeds protocol limits"),
            },
            Err(error) => self.error(frame.request_id, error.code, error.message),
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
                if let Some(queue) = self.pending.remove(&session_id) {
                    self.pending_bytes -= queue.iter().map(|input| input.bytes.len()).sum::<usize>();
                }
                self.graphics.remove(&session_id);
                self.closed_outputs.remove(&session_id);
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

    fn error(&self, request_id: u64, code: ErrorCode, message: &str) -> Frame {
        Frame {
            service: Service::Session,
            kind: FrameKind::Error,
            flags: 0,
            request_id,
            payload: encode_error(code, message).expect("constant error is within limit"),
        }
    }

    fn drain_output(&mut self, id: &str) {
        if let Err(error) = self.stream_output(id) {
            // An unusable PTY or an unconsumed graphics-reply flood belongs to
            // this process, never to the listener or another session.
            eprintln!("droid-sessiond: session {id} output failed: {error:?}");
            let _ = self.dispatch(SessionRequest::Terminate { session_id: id.into(), signal: 9 }, &mut None);
            self.output.tails.entry(id.into()).or_default().exit = Some(-1);
        }
    }

    fn stream_output(
        &mut self,
        session_id: &str,
    ) -> Result<(), VsockError> {
        let session_id = session_id.to_owned();
        let mut buffer = [0_u8; 8192];
        if let Some(bytes) = self.staged_output.remove(&session_id) {
            return self.process_output(&session_id, &bytes);
        }
        match self.sessions.read_output(&session_id, &mut buffer) {
            Ok(size) if size > 0 => self.process_output(&session_id, &buffer[..size]),
            Ok(_) | Err(PtyError::Nix(nix::errno::Errno::EIO)) => {
                if let Some(mut graphics) = self.graphics.remove(&session_id) {
                    self.write_graphics_output(&session_id, graphics.finish())?;
                }
                // PTY EOF and child exit are independent. Keep process ownership
                // for LIST/TERMINATE, but never poll this permanent HUP again.
                self.closed_outputs.insert(session_id);
                self.reap_closed_outputs();
                Ok(())
            }
            Err(PtyError::Nix(nix::errno::Errno::EAGAIN | nix::errno::Errno::EINTR)) => Ok(()),
            Err(_) => Err(VsockError::Io(std::io::Error::other("unable to read PTY output"))),
        }
    }

    fn reap_closed_outputs(&mut self) {
        for id in self.closed_outputs.iter().cloned().collect::<Vec<_>>() {
            if let Ok(Some(exit_code)) = self.sessions.poll_exit(&id) {
                self.closed_outputs.remove(&id);
                self.output.tails.entry(id.clone()).or_default().exit = Some(exit_code);
                if let Some(client) = self.client.as_mut() {
                    if client.attached.as_deref() == Some(id.as_str()) {
                        let _ = self.sessions.detach(&id);
                        client.attached = None;
                    }
                }
            }
        }
    }

    fn process_output(&mut self, id: &str, bytes: &[u8]) -> Result<(), VsockError> {
        let (consumed, output) = self.graphics.entry(id.to_owned()).or_default().accept_prefix(bytes);
        if consumed < bytes.len() { self.staged_output.insert(id.to_owned(), bytes[consumed..].to_vec()); }
        self.write_graphics_output(id, output)
    }

    fn write_graphics_output(
        &mut self, session_id: &str, output: GraphicsOutput,
    ) -> Result<(), VsockError> {
        for reply in output.replies {
            self.queue_input(session_id.to_owned(), reply, None)
                .map_err(|_| std::io::Error::other("unable to queue graphics response"))?;
        }
        for bytes in output.bytes {
            self.output.push(session_id, bytes);
        }
        Ok(())
    }

    fn write_event(
        connection: &mut VsockConnection,
        event: SessionEvent<'_>,
    ) -> Result<(), VsockError> {
        let payload = event.encode().map_err(|_| std::io::Error::other("session event exceeds protocol limits"))?;
        connection.write_frame(&Frame {
            service: Service::Session,
            kind: FrameKind::Stream,
            flags: 0,
            request_id: 0,
            payload,
        })
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
struct IoReady { listener_ready: bool, request_ready: bool, outputs: Vec<usize> }

#[cfg(test)]
fn wait_for_io(connection_fd: std::os::fd::RawFd, pty_fds: &[std::os::fd::RawFd]) -> Result<IoReady, VsockError> {
    poll_runtime(-1, connection_fd, pty_fds, true, false, &vec![false; pty_fds.len()], -1)
}
fn poll_runtime(listener_fd: std::os::fd::RawFd, connection_fd: std::os::fd::RawFd, pty_fds: &[std::os::fd::RawFd],
    capacity: bool, network_write: bool, pending: &[bool], timeout: i32) -> Result<IoReady, VsockError> {
    let mut descriptors = vec![
        nix::libc::pollfd { fd: listener_fd, events: nix::libc::POLLIN, revents: 0 },
        nix::libc::pollfd { fd: connection_fd,
            events: (if capacity { nix::libc::POLLIN } else { 0 }) | (if network_write { nix::libc::POLLOUT } else { 0 }),
            revents: 0 },
    ];
    for (index, &fd) in pty_fds.iter().enumerate() {
        descriptors.push(nix::libc::pollfd { fd, events: nix::libc::POLLIN |
            if pending[index] { nix::libc::POLLOUT } else { 0 }, revents: 0 });
    }
    loop {
        let result = unsafe { nix::libc::poll(descriptors.as_mut_ptr(), descriptors.len() as _, timeout) };
        if result >= 0 { break; }
        if nix::errno::Errno::last() != nix::errno::Errno::EINTR { return Err(VsockError::Socket(nix::errno::Errno::last())); }
    }
    let connection_events = descriptors[1].revents;
    Ok(IoReady {
        listener_ready: descriptors[0].revents & nix::libc::POLLIN != 0,
        request_ready: connection_events & (nix::libc::POLLIN | nix::libc::POLLHUP | nix::libc::POLLERR) != 0,
        outputs: descriptors.iter().skip(2).enumerate().filter_map(|(index, entry)|
            (entry.revents & (nix::libc::POLLIN | nix::libc::POLLHUP | nix::libc::POLLERR) != 0).then_some(index)).collect(),
    })
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
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixStream;

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
            while !matches!(server.sessions.get(&id).unwrap().state, crate::session::SessionState::Exited(7)) {
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
        while !matches!(server.sessions.get(&id).unwrap().state, crate::session::SessionState::Exited(9)) {
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

    #[test]
    fn polls_every_session_and_host_requests_without_a_selected_renderer() {
        let (host, mut requests) = UnixStream::pair().unwrap();
        let (first, mut first_writer) = UnixStream::pair().unwrap();
        let (second, mut second_writer) = UnixStream::pair().unwrap();
        second_writer.write_all(b"background").unwrap();
        let ready = wait_for_io(host.as_raw_fd(), &[first.as_raw_fd(), second.as_raw_fd()]).unwrap();
        assert!(!ready.request_ready);
        assert_eq!(ready.outputs, vec![1]);
        first_writer.write_all(b"other terminal").unwrap();
        requests.write_all(b"request").unwrap();
        let ready = wait_for_io(host.as_raw_fd(), &[first.as_raw_fd(), second.as_raw_fd()]).unwrap();
        assert!(ready.request_ready);
        assert_eq!(ready.outputs, vec![0, 1]);
    }
}
