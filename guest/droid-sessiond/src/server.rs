//! Serial v1 session dispatcher. Serial dispatch keeps the first transport
//! spike simple; PTY output fan-out is added separately without changing it.

use crate::graphics_transport::{GraphicsTransport, Output as GraphicsOutput};
use std::collections::HashMap;
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
}

impl Default for SessionServer {
    fn default() -> Self { Self { sessions: SessionRegistry::default(), graphics: HashMap::new() } }
}

impl SessionServer {
    pub fn serve_forever(&mut self, listener: VsockListener) -> Result<(), VsockError> {
        loop {
            let connection = listener.accept()?;
            if let Err(error) = self.serve_connection(connection) {
                eprintln!("droid-sessiond: client disconnected or request failed: {error:?}");
            }
        }
    }

    fn serve_connection(&mut self, mut connection: VsockConnection) -> Result<(), VsockError> {
        let mut attached = None;
        let result = self.serve_connection_loop(&mut connection, &mut attached);
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
            let pty_fd = attached.as_deref().and_then(|id| self.sessions.output_fd(id).ok());
            let ready = wait_for_io(connection.raw_fd(), pty_fd)?;
            if ready.output_ready {
                self.stream_output(connection, attached)?;
            }
            if ready.request_ready {
                let frame = connection.read_frame()?;
                let response = self.handle(frame, attached);
                connection.write_frame(&response)?;
            }
        }
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
    ) -> Result<(), VsockError> {
        let session_id = match attached.clone() { Some(id) => id, None => return Ok(()) };
        let mut buffer = [0_u8; 8192];
        match self.sessions.read_output(&session_id, &mut buffer) {
            Ok(size) if size > 0 => {
                let resolved = self.graphics.entry(session_id.clone()).or_default().accept(&buffer[..size]);
                self.write_graphics_output(connection, &session_id, resolved)
            },
            Ok(_) | Err(PtyError::Nix(nix::errno::Errno::EIO)) => {
                if let Some(mut graphics) = self.graphics.remove(&session_id) {
                    self.write_graphics_output(connection, &session_id, graphics.finish())?;
                }
                let exit_code = self.sessions.poll_exit(&session_id)
                    .map_err(|_| std::io::Error::other("unable to inspect exited session"))?
                    .ok_or_else(|| std::io::Error::other("PTY closed before its child exit status was available"))?;
                self.write_event(connection, SessionEvent::Exited { session_id: &session_id, exit_code })?;
                let _ = self.sessions.detach(&session_id);
                *attached = None;
                Ok(())
            }
            Err(_) => Err(VsockError::Io(std::io::Error::other("unable to read PTY output"))),
        }
    }

    fn write_graphics_output(
        &mut self, connection: &mut VsockConnection, session_id: &str, output: GraphicsOutput,
    ) -> Result<(), VsockError> {
        for reply in output.replies {
            self.sessions.write_input(session_id, &reply)
                .map_err(|_| std::io::Error::other("unable to deliver graphics response"))?;
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

struct IoReady { request_ready: bool, output_ready: bool }

fn wait_for_io(connection_fd: std::os::fd::RawFd, pty_fd: Option<std::os::fd::RawFd>) -> Result<IoReady, VsockError> {
    let mut descriptors = vec![nix::libc::pollfd {
        fd: connection_fd,
        events: nix::libc::POLLIN,
        revents: 0,
    }];
    if let Some(fd) = pty_fd {
        descriptors.push(nix::libc::pollfd { fd, events: nix::libc::POLLIN, revents: 0 });
    }
    let result = unsafe { nix::libc::poll(descriptors.as_mut_ptr(), descriptors.len() as _, -1) };
    if result < 0 { return Err(VsockError::Socket(nix::errno::Errno::last())); }
    let connection_events = descriptors[0].revents;
    let output_events = descriptors.get(1).map(|entry| entry.revents).unwrap_or(0);
    Ok(IoReady {
        request_ready: connection_events & (nix::libc::POLLIN | nix::libc::POLLHUP | nix::libc::POLLERR) != 0,
        output_ready: output_events & (nix::libc::POLLIN | nix::libc::POLLHUP | nix::libc::POLLERR) != 0,
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
