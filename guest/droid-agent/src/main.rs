//! The guest-side half of Android integration. Local callers use a private Unix
//! socket; only this process accepts the host-CID vsock bridge connection.

use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::mem::size_of;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const PORT: u32 = 40_501;
const SOCKET: &str = "/run/droid/droid-agent.sock";
const MAX: usize = 1_048_576;
const HOST_CID: u32 = 2;
const MAGIC: u32 = 0x4450_5459;

#[repr(C)]
struct SockaddrVm { family: nix::libc::sa_family_t, reserved: u16, port: u32, cid: u32, zero: [u8; 4] }

fn main() -> io::Result<()> {
    let host = Arc::new(Mutex::new(None::<File>));
    let host_for_thread = host.clone();
    thread::spawn(move || accept_host_connections(host_for_thread));

    let _ = fs::remove_file(SOCKET);
    let listener = UnixListener::bind(SOCKET)?;
    fs::set_permissions(SOCKET, std::os::unix::fs::PermissionsExt::from_mode(0o660))?;
    for stream in listener.incoming() {
        if let Ok(stream) = stream { handle_cli(stream, &host); }
    }
    Ok(())
}

fn accept_host_connections(host: Arc<Mutex<Option<File>>>) {
    loop {
        match bind_vsock(PORT).and_then(|listener| accept_host(&listener)) {
            Ok(connection) => *host.lock().expect("host lock") = Some(connection),
            Err(_) => thread::sleep(Duration::from_secs(2)),
        }
    }
}

fn bind_vsock(port: u32) -> io::Result<OwnedFd> {
    let raw = unsafe { nix::libc::socket(nix::libc::AF_VSOCK, nix::libc::SOCK_STREAM | nix::libc::SOCK_CLOEXEC, 0) };
    if raw < 0 { return Err(io::Error::last_os_error()); }
    let socket = unsafe { OwnedFd::from_raw_fd(raw) };
    let address = SockaddrVm { family: nix::libc::AF_VSOCK as _, reserved: 0, port, cid: u32::MAX, zero: [0; 4] };
    let result = unsafe { nix::libc::bind(socket.as_raw_fd(), &address as *const _ as *const nix::libc::sockaddr, size_of::<SockaddrVm>() as _) };
    if result < 0 { return Err(io::Error::last_os_error()); }
    if unsafe { nix::libc::listen(socket.as_raw_fd(), 4) } < 0 { return Err(io::Error::last_os_error()); }
    Ok(socket)
}

fn accept_host(listener: &OwnedFd) -> io::Result<File> {
    let mut address = SockaddrVm { family: 0, reserved: 0, port: 0, cid: 0, zero: [0; 4] };
    let mut length = size_of::<SockaddrVm>() as nix::libc::socklen_t;
    let raw = unsafe { nix::libc::accept4(listener.as_raw_fd(), &mut address as *mut _ as *mut nix::libc::sockaddr, &mut length, nix::libc::SOCK_CLOEXEC) };
    if raw < 0 { return Err(io::Error::last_os_error()); }
    if address.cid != HOST_CID { unsafe { nix::libc::close(raw) }; return Err(io::Error::new(io::ErrorKind::PermissionDenied, "non-host vsock peer")); }
    Ok(unsafe { File::from_raw_fd(raw) })
}

fn handle_cli(mut client: UnixStream, host: &Arc<Mutex<Option<File>>>) {
    let mut command = Vec::new();
    if client.read_to_end(&mut command).is_err() { return; }
    let payload = match command_to_payload(&command) { Ok(value) => value, Err(message) => { let _ = client.write_all(format!("ERR\0{message}").as_bytes()); return; } };
    let mut guard = host.lock().expect("host lock");
    let Some(connection) = guard.as_mut() else { let _ = client.write_all(b"ERR\0Android bridge is disconnected"); return; };
    let _ = send_lifecycle(connection, 1); // WORK_ACTIVE
    let result = send_bridge_request(connection, payload);
    let _ = send_lifecycle(connection, 2); // WORK_IDLE
    match result { Ok(value) => { let _ = client.write_all(&value); }, Err(message) => { *guard = None; let _ = client.write_all(format!("ERR\0{message}").as_bytes()); } }
}

fn send_lifecycle(stream: &mut File, state: u8) -> io::Result<()> {
    let payload = [state];
    let mut frame = Vec::with_capacity(25);
    frame.extend_from_slice(&MAGIC.to_be_bytes()); frame.extend_from_slice(&1u16.to_be_bytes());
    frame.extend_from_slice(&[2, 3]); frame.extend_from_slice(&[0; 4]); frame.extend_from_slice(&0u64.to_be_bytes());
    frame.extend_from_slice(&1u32.to_be_bytes()); frame.extend_from_slice(&payload);
    stream.write_all(&frame)?; stream.flush()
}

fn command_to_payload(command: &[u8]) -> Result<Vec<u8>, &'static str> {
    if command == b"clipboard get" { return Ok(vec![1]); }
    if command == b"battery" { return Ok(vec![5]); }
    if let Some(value) = command.strip_prefix(b"clipboard set\0") { return strings(2, &[value]); }
    if let Some(value) = command.strip_prefix(b"open\0") { return strings(3, &[value]); }
    if let Some(value) = command.strip_prefix(b"notify\0") { let mut fields = value.splitn(2, |b| *b == 0); return strings(4, &[fields.next().ok_or("missing title")?, fields.next().ok_or("missing text")?]); }
    Err("unsupported droid command")
}

fn strings(code: u8, values: &[&[u8]]) -> Result<Vec<u8>, &'static str> { let mut output = vec![code]; for value in values { if value.len() > u16::MAX as usize { return Err("argument too large"); } output.extend_from_slice(&(value.len() as u16).to_be_bytes()); output.extend_from_slice(value); } Ok(output) }

fn send_bridge_request(stream: &mut File, payload: Vec<u8>) -> Result<Vec<u8>, String> {
    let mut frame = Vec::with_capacity(24 + payload.len()); frame.extend_from_slice(&MAGIC.to_be_bytes()); frame.extend_from_slice(&1u16.to_be_bytes()); frame.extend_from_slice(&[3, 1]); frame.extend_from_slice(&[0; 4]); frame.extend_from_slice(&1u64.to_be_bytes()); frame.extend_from_slice(&(payload.len() as u32).to_be_bytes()); frame.extend_from_slice(&payload);
    stream.write_all(&frame).map_err(|e| e.to_string())?; stream.flush().map_err(|e| e.to_string())?;
    let mut header = [0u8; 24]; stream.read_exact(&mut header).map_err(|e| e.to_string())?; if u32::from_be_bytes(header[0..4].try_into().unwrap()) != MAGIC { return Err("invalid host response".into()); }
    let size = u32::from_be_bytes(header[20..24].try_into().unwrap()) as usize; if size > MAX { return Err("oversized host response".into()); } let mut payload = vec![0; size]; stream.read_exact(&mut payload).map_err(|e| e.to_string())?;
    if header[7] == 5 { return Err(String::from_utf8_lossy(&payload).into_owned()); }
    if header[7] != 2 { return Err("unexpected host frame".into()); }
    Ok(match payload.first() { Some(0x80) => { let len = u16::from_be_bytes([payload[1], payload[2]]) as usize; [b"OK\0".as_slice(), &payload[3..3 + len]].concat() }, Some(0x81) => b"OK\0".to_vec(), _ => return Err("invalid bridge response".into()) })
}
