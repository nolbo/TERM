//! Reports guest TCP listening ports to the host on dedicated vsock port 40502.
//! It reports non-privileged ports only; a later proxy component applies the
//! host exposure policy, including whether loopback listeners are eligible.

use std::collections::BTreeSet;
use std::fs::File;
use std::io::{self, Read, Write};
use std::mem::size_of;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::thread;
use std::time::Duration;

const PORT: u32 = 40_502;
const MAGIC: u32 = 0x4450_5459;

#[repr(C)] struct SockaddrVm { family: nix::libc::sa_family_t, reserved: u16, port: u32, cid: u32, zero: [u8; 4] }

fn main() {
    loop {
        if let Ok(mut host) = accept_host() { report_loop(&mut host); }
        thread::sleep(Duration::from_secs(2));
    }
}

fn accept_host() -> io::Result<File> {
    let raw = unsafe { nix::libc::socket(nix::libc::AF_VSOCK, nix::libc::SOCK_STREAM | nix::libc::SOCK_CLOEXEC, 0) };
    if raw < 0 { return Err(io::Error::last_os_error()); }
    let socket = unsafe { OwnedFd::from_raw_fd(raw) };
    let address = SockaddrVm { family: nix::libc::AF_VSOCK as _, reserved: 0, port: PORT, cid: u32::MAX, zero: [0; 4] };
    if unsafe { nix::libc::bind(socket.as_raw_fd(), &address as *const _ as *const nix::libc::sockaddr, size_of::<SockaddrVm>() as _) } < 0 { return Err(io::Error::last_os_error()); }
    if unsafe { nix::libc::listen(socket.as_raw_fd(), 1) } < 0 { return Err(io::Error::last_os_error()); }
    let mut peer = SockaddrVm { family: 0, reserved: 0, port: 0, cid: 0, zero: [0; 4] }; let mut len = size_of::<SockaddrVm>() as nix::libc::socklen_t;
    let client = unsafe { nix::libc::accept4(socket.as_raw_fd(), &mut peer as *mut _ as *mut nix::libc::sockaddr, &mut len, nix::libc::SOCK_CLOEXEC) };
    if client < 0 { return Err(io::Error::last_os_error()); }
    if peer.cid != 2 { unsafe { nix::libc::close(client) }; return Err(io::Error::new(io::ErrorKind::PermissionDenied, "untrusted peer")); }
    Ok(unsafe { File::from_raw_fd(client) })
}

fn report_loop(host: &mut File) {
    let mut previous = BTreeSet::new();
    loop {
        let current = listening_ports();
        for port in current.difference(&previous) { if send(host, 1, *port).is_err() { return; } }
        for port in previous.difference(&current) { if send(host, 2, *port).is_err() { return; } }
        previous = current;
        thread::sleep(Duration::from_secs(2));
    }
}

fn listening_ports() -> BTreeSet<u16> {
    ["/proc/net/tcp", "/proc/net/tcp6"].iter().flat_map(|path| std::fs::read_to_string(path).unwrap_or_default().lines().skip(1).filter_map(parse)).collect()
}

fn parse(line: &str) -> Option<u16> {
    let fields: Vec<_> = line.split_whitespace().collect(); if fields.get(3)? != &"0A" { return None; }
    let address = fields.get(1)?; let (_, port) = address.split_once(':')?; let port = u16::from_str_radix(port, 16).ok()?;
    (port >= 1024).then_some(port)
}

fn send(host: &mut File, kind: u8, port: u16) -> io::Result<()> {
    let payload = if kind == 1 { vec![1, (port >> 8) as u8, port as u8, 0, 0] } else { vec![2, (port >> 8) as u8, port as u8] };
    let mut frame = Vec::new(); frame.extend_from_slice(&MAGIC.to_be_bytes()); frame.extend_from_slice(&1u16.to_be_bytes()); frame.extend_from_slice(&[4, 3]); frame.extend_from_slice(&[0; 4]); frame.extend_from_slice(&0u64.to_be_bytes()); frame.extend_from_slice(&(payload.len() as u32).to_be_bytes()); frame.extend_from_slice(&payload);
    host.write_all(&frame)?; host.flush()
}
