//! Serial v1 dispatcher. Every live PTY is drained while the host is connected;
//! renderer attachment never determines which terminal states the host can preserve.

use crate::graphics_transport::{GraphicsTransport, Output as GraphicsOutput};
use std::collections::{HashMap, VecDeque};
use crate::protocol::{encode_error, ErrorCode, Frame, FrameKind, Service};
use crate::pty::PtyError;
use crate::session::SessionRegistry;
use crate::session_event::SessionEvent;
use crate::session_protocol::SessionRequest;
use crate::session_response::{SessionDescriptor, SessionResponse};
use crate::vsock::{VsockConnection, VsockError, VsockListener};

pub struct SessionServer {
    sessions: SessionRegistry,
    graphics: HashMap<String, GraphicsTransport>,
    pending: HashMap<String, VecDeque<PendingInput>>,
    pending_bytes: usize,
    staged_output: HashMap<String, Vec<u8>>,
}

impl Default for SessionServer {
    fn default() -> Self { Self { sessions: SessionRegistry::default(), graphics: HashMap::new(), pending: HashMap::new(), pending_bytes: 0, staged_output: HashMap::new() } }
}

impl SessionServer {
    pub fn serve_forever(&mut self, listener: VsockListener) -> Result<(), VsockError> {
        loop {
            let connection = match listener.accept() {
                Ok(connection) => connection,
                Err(VsockError::UntrustedPeer) => continue,
                Err(error) => return Err(error),
            };
            if let Err(error) = self.serve_connection(connection) {
                eprintln!("droid-sessiond: client disconnected or request failed: {error:?}");
            }
        }
    }

    fn serve_connection(&mut self, mut connection: VsockConnection) -> Result<(), VsockError> {
        connection.enable_nonblocking()?;
        let mut attached = None;
        let result = self.serve_connection_loop(&mut connection, &mut attached);
        // A lost acknowledgement is an unknown result; never replay pending input
        // into a new logical connection. The guest processes themselves survive.
        self.pending.clear(); self.pending_bytes = 0; self.staged_output.clear();
        if let Some(session_id) = attached {
            let _ = self.sessions.detach(&session_id);
        }
        result
    }

    fn serve_connection_loop(
        &mut self,
        connection: &mut VsockConnection,
        attached: &mut Option<String>,
    ) -> Result<(), VsockError> {
        loop {
            connection.flush_output()?;
            if connection.has_capacity() { self.drain_input(connection)?; }
            for id in self.staged_output.keys().cloned().collect::<Vec<_>>() {
                if connection.has_pty_capacity() { self.stream_output(connection, attached, &id)?; }
            }
            let outputs: Vec<_> = self.sessions.running()
                .filter_map(|session| self.sessions.output_fd(&session.id).ok().map(|fd| (session.id.clone(), fd)))
                .collect();
            let descriptors: Vec<_> = outputs.iter().map(|(_, fd)| *fd).collect();
            let writable: Vec<_> = outputs.iter().map(|(id, _)| self.pending.contains_key(id)).collect();
            let ready = poll_runtime(connection.raw_fd(), &descriptors, connection.has_capacity(),
                connection.wants_write(), &writable, connection.has_pty_capacity())?;
            for index in ready.outputs {
                if connection.has_pty_capacity() { self.stream_output(connection, attached, &outputs[index].0)?; }
            }
            if ready.request_ready && connection.has_capacity() {
                if let Some(frame) = connection.read_frame_ready()? {
                    if frame.service == Service::Session && frame.kind == FrameKind::Request {
                        if let Ok(SessionRequest::Write { session_id, bytes }) = SessionRequest::decode(&frame.payload) {
                            if let Err(error) = self.queue_input(session_id, bytes, Some(frame.request_id)) {
                                connection.write_frame(&self.error(frame.request_id, error.code, error.message))?;
                            }
                            continue;
                        }
                    }
                    let response = self.handle(frame, attached);
                    connection.write_frame(&response)?;
                }
            }
        }
    }

    fn queue_input(&mut self, id: String, bytes: Vec<u8>, request: Option<u64>) -> Result<(), DispatchError> {
        if self.sessions.output_fd(&id).is_err() { return Err(DispatchError::not_found("session not found")); }
        let count: usize = self.pending.values().map(VecDeque::len).sum();
        if count >= 256 || bytes.len() > 4 * 1024 * 1024 - self.pending_bytes {
            return Err(DispatchError { code: ErrorCode::GuestError, message: "session input queue is full" });
        }
        self.pending_bytes += bytes.len();
        self.pending.entry(id).or_default().push_back(PendingInput { bytes, offset: 0, request });
        Ok(())
    }

    fn drain_input(&mut self, connection: &mut VsockConnection) -> Result<(), VsockError> {
        let ids: Vec<_> = self.pending.keys().cloned().collect();
        for id in ids {
            if !connection.has_capacity() { break; }
            let queue = self.pending.get_mut(&id).unwrap();
            let input = queue.front_mut().unwrap();
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
                    connection.write_frame(&response)?;
                }
            }
            if self.pending.get(&id).is_some_and(VecDeque::is_empty) { self.pending.remove(&id); }
        }
        Ok(())
    }

    fn handle(&mut self, frame: Frame, attached: &mut Option<String>) -> Frame {
        if frame.service != Service::Session || frame.kind != FrameKind::Request {
            return self.error(frame.request_id, ErrorCode::Unsupported, "only session requests are accepted");
        }
        let request = match SessionRequest::decode(&frame.payload) {
            Ok(request) => request,
            Err(_) => return self.error(frame.request_id, ErrorCode::InvalidArgument, "invalid session request"),
        };
        match self.dispatch(request, attached) {
            Ok(response) => match response.encode() {
                Ok(payload) => Frame {
                    service: Service::Session,
                    kind: FrameKind::Response,
                    flags: 0,
                    request_id: frame.request_id,
                    payload,
                },
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
                self.graphics.remove(&session_id);
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

    fn stream_output(
        &mut self,
        connection: &mut VsockConnection,
        attached: &mut Option<String>,
        session_id: &str,
    ) -> Result<(), VsockError> {
        let session_id = session_id.to_owned();
        let mut buffer = [0_u8; 8192];
        if let Some(bytes) = self.staged_output.remove(&session_id) {
            return self.process_output(connection, &session_id, &bytes);
        }
        match self.sessions.read_output(&session_id, &mut buffer) {
            Ok(size) if size > 0 => self.process_output(connection, &session_id, &buffer[..size]),
            Ok(_) | Err(PtyError::Nix(nix::errno::Errno::EIO)) => {
                if let Some(mut graphics) = self.graphics.remove(&session_id) {
                    self.write_graphics_output(connection, &session_id, graphics.finish())?;
                }
                let exit_code = self.sessions.poll_exit(&session_id)
                    .map_err(|_| std::io::Error::other("unable to inspect exited session"))?
                    .ok_or_else(|| std::io::Error::other("PTY closed before its child exit status was available"))?;
                self.write_event(connection, SessionEvent::Exited { session_id: &session_id, exit_code })?;
                if attached.as_deref() == Some(session_id.as_str()) {
                    let _ = self.sessions.detach(&session_id);
                    *attached = None;
                }
                Ok(())
            }
            Err(PtyError::Nix(nix::errno::Errno::EAGAIN | nix::errno::Errno::EINTR)) => Ok(()),
            Err(_) => Err(VsockError::Io(std::io::Error::other("unable to read PTY output"))),
        }
    }

    fn process_output(&mut self, connection: &mut VsockConnection, id: &str, bytes: &[u8]) -> Result<(), VsockError> {
        let (consumed, output) = self.graphics.entry(id.to_owned()).or_default().accept_prefix(bytes);
        if consumed < bytes.len() { self.staged_output.insert(id.to_owned(), bytes[consumed..].to_vec()); }
        self.write_graphics_output(connection, id, output)
    }

    fn write_graphics_output(
        &mut self, connection: &mut VsockConnection, session_id: &str, output: GraphicsOutput,
    ) -> Result<(), VsockError> {
        for reply in output.replies {
            self.queue_input(session_id.to_owned(), reply, None)
                .map_err(|_| std::io::Error::other("unable to queue graphics response"))?;
        }
        for bytes in output.bytes {
            self.write_event(connection, SessionEvent::Output { session_id, bytes: &bytes })?;
        }
        Ok(())
    }

    fn write_event(
        &self,
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
struct IoReady { request_ready: bool, outputs: Vec<usize> }

#[cfg(test)]
fn wait_for_io(connection_fd: std::os::fd::RawFd, pty_fds: &[std::os::fd::RawFd]) -> Result<IoReady, VsockError> {
    poll_runtime(connection_fd, pty_fds, true, false, &vec![false; pty_fds.len()], true)
}
fn poll_runtime(connection_fd: std::os::fd::RawFd, pty_fds: &[std::os::fd::RawFd],
    capacity: bool, network_write: bool, pending: &[bool], pty_capacity: bool) -> Result<IoReady, VsockError> {
    let mut descriptors = vec![nix::libc::pollfd {
        fd: connection_fd,
        events: (if capacity { nix::libc::POLLIN } else { 0 }) | (if network_write { nix::libc::POLLOUT } else { 0 }),
        revents: 0,
    }];
    let mut indices = Vec::new();
    if capacity {
        for (index, &fd) in pty_fds.iter().enumerate() {
            if !pty_capacity && !pending[index] { continue; }
            indices.push(index);
            descriptors.push(nix::libc::pollfd { fd, events: (if pty_capacity { nix::libc::POLLIN } else { 0 }) |
                if pending[index] { nix::libc::POLLOUT } else { 0 }, revents: 0 });
        }
    }
    loop {
        let result = unsafe { nix::libc::poll(descriptors.as_mut_ptr(), descriptors.len() as _, -1) };
        if result >= 0 { break; }
        if nix::errno::Errno::last() != nix::errno::Errno::EINTR { return Err(VsockError::Socket(nix::errno::Errno::last())); }
    }
    let connection_events = descriptors[0].revents;
    Ok(IoReady {
        request_ready: connection_events & (nix::libc::POLLIN | nix::libc::POLLHUP | nix::libc::POLLERR) != 0,
        outputs: descriptors.iter().skip(1).enumerate().filter_map(|(index, entry)|
            (entry.revents & (nix::libc::POLLIN | nix::libc::POLLHUP | nix::libc::POLLERR) != 0).then_some(indices[index])).collect(),
    })
}

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
