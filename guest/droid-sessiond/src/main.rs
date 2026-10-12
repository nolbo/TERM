mod graphics_transport;
mod protocol;
mod pty;
mod session;
mod session_event;
mod session_protocol;
mod session_response;
mod server;
mod vsock;

use protocol::{Frame, FrameKind, Service};
use session::SessionRegistry;
use session_protocol::SessionRequest;
use server::SessionServer;

fn main() {
    if std::env::args().any(|argument| argument == "--self-check") {
        let mut sessions = SessionRegistry::default();
        let session = sessions.create_shell("/bin/sh", "/home/user", 24, 80);
        let frame = Frame {
            service: Service::Session,
            kind: FrameKind::Response,
            flags: 0,
            request_id: 1,
            payload: session.id.clone().into_bytes(),
        };
        Frame::decode(&frame.encode().expect("frame encoding")).expect("frame decoding");
        let request = SessionRequest::Create {
            shell: "/bin/sh".into(),
            cwd: "/home/user".into(),
            env: vec![("TERM".into(), "xterm-256color".into())],
            rows: 24,
            cols: 80,
        };
        SessionRequest::decode(&request.encode().expect("session encoding")).expect("session decoding");
        println!("droid-sessiond protocol and registry self-check passed");
        println!("sessiond vsock port is {}", vsock::SESSIOND_PORT);
        return;
    }

    let listener = vsock::VsockListener::bind(vsock::SESSIOND_PORT)
        .expect("droid-sessiond: failed to bind host-only vsock listener");
    eprintln!("droid-sessiond: serving session protocol on vsock port {}", vsock::SESSIOND_PORT);
    SessionServer::default().serve_forever(listener)
        .expect("droid-sessiond: vsock listener terminated");
}
