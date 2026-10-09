//! Bounded TCP listener reports (40502) and host-CID-only TCP proxy (40504).
use std::collections::BTreeSet;
use std::fs::File;
use std::io::{self, Read, Write};
use std::mem::size_of;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpStream};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};
use std::thread;
use std::time::Duration;

const REPORT_PORT: u32 = 40_502;
const PROXY_PORT: u32 = 40_504;
const MAGIC: u32 = 0x4450_5459;
const MAX_ENDPOINTS: usize = 128;
const MAX_CONNECTIONS: usize = 64;
#[repr(C)]
struct SockaddrVm { family: nix::libc::sa_family_t, reserved: u16, port: u32, cid: u32, zero: [u8; 4] }
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
struct Endpoint { address: IpAddr, port: u16 }

fn main() -> io::Result<()> {
    // Never advertise a proxy that failed to bind.
    let proxy = bind_vsock(PROXY_PORT)?;
    thread::spawn(move || serve_proxy(proxy));
    let reporter = bind_vsock(REPORT_PORT)?;
    loop {
        if let Ok(mut host) = accept_host(&reporter) { let _ = report_loop(&mut host); }
        thread::sleep(Duration::from_secs(2));
    }
}
fn bind_vsock(port: u32) -> io::Result<OwnedFd> {
    let raw = unsafe { nix::libc::socket(nix::libc::AF_VSOCK, nix::libc::SOCK_STREAM | nix::libc::SOCK_CLOEXEC, 0) };
    if raw < 0 { return Err(io::Error::last_os_error()); }
    let socket = unsafe { OwnedFd::from_raw_fd(raw) };
    let address = SockaddrVm { family: nix::libc::AF_VSOCK as _, reserved: 0, port, cid: u32::MAX, zero: [0; 4] };
    if unsafe { nix::libc::bind(socket.as_raw_fd(), &address as *const _ as *const nix::libc::sockaddr, size_of::<SockaddrVm>() as _) } < 0 { return Err(io::Error::last_os_error()); }
    if unsafe { nix::libc::listen(socket.as_raw_fd(), 16) } < 0 { return Err(io::Error::last_os_error()); }
    Ok(socket)
}
fn accept_host(listener: &OwnedFd) -> io::Result<File> {
    let mut peer = SockaddrVm { family: 0, reserved: 0, port: 0, cid: 0, zero: [0; 4] };
    let mut len = size_of::<SockaddrVm>() as nix::libc::socklen_t;
    let client = unsafe { nix::libc::accept4(listener.as_raw_fd(), &mut peer as *mut _ as *mut nix::libc::sockaddr, &mut len, nix::libc::SOCK_CLOEXEC) };
    if client < 0 { return Err(io::Error::last_os_error()); }
    let file = unsafe { File::from_raw_fd(client) };
    if peer.cid != 2 { return Err(io::Error::new(io::ErrorKind::PermissionDenied, "untrusted peer")); }
    Ok(file)
}
fn report_loop(host: &mut File) -> io::Result<()> {
    socket_timeout(host, 5)?;
    let mut previous = BTreeSet::new();
    let mut initial = true;
    loop {
        // An unreadable table is unknown, never a fabricated listener-close snapshot.
        let observed = listening_endpoints()?;
        // Keep already reported listeners until they actually disappear. A newly
        // lower-sorted endpoint must not turn the reporting limit into a false close.
        let mut current: BTreeSet<_> = previous.intersection(&observed).cloned().collect();
        for endpoint in &observed {
            if current.len() >= MAX_ENDPOINTS { break; }
            current.insert(endpoint.clone());
        }
        if initial { send(host, &[5, 1])?; }
        for endpoint in current.difference(&previous) { send_endpoint(host, 3, endpoint)?; }
        for endpoint in previous.difference(&current) { send_endpoint(host, 4, endpoint)?; }
        // Exact legacy events let old hosts continue to consume their original wire format.
        let old_ports: BTreeSet<_> = previous.iter().map(|e| e.port).collect();
        let new_ports: BTreeSet<_> = current.iter().map(|e| e.port).collect();
        for port in new_ports.difference(&old_ports) { send(host, &[1, (port >> 8) as u8, *port as u8, 0, 0])?; }
        for port in old_ports.difference(&new_ports) { send(host, &[2, (port >> 8) as u8, *port as u8])?; }
        if initial { send(host, &[6])?; initial = false; }
        previous = current;
        thread::sleep(Duration::from_secs(2));
    }
}
fn listening_endpoints() -> io::Result<BTreeSet<Endpoint>> {
    let mut endpoints = BTreeSet::new();
    for (path, ipv6) in [("/proc/net/tcp", false), ("/proc/net/tcp6", true)] {
        let contents = match std::fs::read_to_string(path) {
            Ok(value) => value,
            Err(error) if ipv6 && error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        endpoints.extend(contents.lines().skip(1).filter_map(|line| parse(line, ipv6)));
    }
    Ok(endpoints)
}
fn parse(line: &str, ipv6: bool) -> Option<Endpoint> {
    let fields: Vec<_> = line.split_whitespace().collect();
    if fields.get(3)? != &"0A" { return None; }
    let (address, port) = fields.get(1)?.split_once(':')?;
    let port = u16::from_str_radix(port, 16).ok()?;
    if port == 0 { return None; }
    let address = if ipv6 {
        if address.len() != 32 { return None; }
        let mut bytes = [0u8; 16];
        for index in 0..4 { bytes[index*4..index*4+4].copy_from_slice(&u32::from_str_radix(&address[index*8..index*8+8], 16).ok()?.to_le_bytes()); }
        IpAddr::V6(Ipv6Addr::from(bytes))
    } else {
        if address.len() != 8 { return None; }
        IpAddr::V4(Ipv4Addr::from(u32::from_str_radix(address, 16).ok()?.to_le_bytes()))
    };
    Some(Endpoint { address, port })
}
fn send_endpoint(host: &mut File, kind: u8, endpoint: &Endpoint) -> io::Result<()> {
    let address = endpoint.address.to_string();
    let mut payload = vec![kind, (endpoint.port >> 8) as u8, endpoint.port as u8, address.len() as u8];
    payload.extend_from_slice(address.as_bytes());
    if kind == 3 { payload.extend_from_slice(&[0, 0]); }
    send(host, &payload)
}
fn send(host: &mut File, payload: &[u8]) -> io::Result<()> {
    let mut frame = Vec::with_capacity(24 + payload.len());
    frame.extend_from_slice(&MAGIC.to_be_bytes()); frame.extend_from_slice(&1u16.to_be_bytes());
    frame.extend_from_slice(&[4, 3]); frame.extend_from_slice(&[0; 4]); frame.extend_from_slice(&0u64.to_be_bytes());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes()); frame.extend_from_slice(payload);
    host.write_all(&frame)?; host.flush()
}
fn socket_timeout(file: &File, seconds: i64) -> io::Result<()> {
    let value = nix::libc::timeval { tv_sec: seconds, tv_usec: 0 };
    for option in [nix::libc::SO_RCVTIMEO, nix::libc::SO_SNDTIMEO] {
        if unsafe { nix::libc::setsockopt(file.as_raw_fd(), nix::libc::SOL_SOCKET, option,
            &value as *const _ as *const _, size_of::<nix::libc::timeval>() as _) } < 0 { return Err(io::Error::last_os_error()); }
    }
    Ok(())
}
fn serve_proxy(listener: OwnedFd) {
    let active = Arc::new(AtomicUsize::new(0));
    loop {
        if let Ok(host) = accept_host(&listener) {
            if active.fetch_update(Ordering::AcqRel, Ordering::Acquire,
                |value| (value < MAX_CONNECTIONS).then_some(value + 1)).is_err() { continue; }
            let active = active.clone();
            thread::spawn(move || { let _ = proxy(host); active.fetch_sub(1, Ordering::AcqRel); });
        } else { thread::sleep(Duration::from_millis(100)); }
    }
}
fn read_target(host: &mut impl Read) -> io::Result<Endpoint> {
    let invalid = || io::Error::new(io::ErrorKind::InvalidInput, "invalid proxy target");
    let mut header = [0; 4]; host.read_exact(&mut header)?;
    if header[0] != 1 || !(2..=45).contains(&header[3]) { return Err(invalid()); }
    let port = u16::from_be_bytes([header[1], header[2]]);
    let mut encoded = vec![0; header[3] as usize]; host.read_exact(&mut encoded)?;
    let text = std::str::from_utf8(&encoded).map_err(|_| invalid())?;
    let address = text.parse::<IpAddr>().map_err(|_| invalid())?;
    if port == 0 { return Err(invalid()); }
    Ok(Endpoint { address, port })
}
fn local_target(endpoint: &Endpoint) -> SocketAddr {
    let address = match endpoint.address {
        IpAddr::V4(value) if value.is_unspecified() => IpAddr::V4(Ipv4Addr::LOCALHOST),
        IpAddr::V6(value) if value.is_unspecified() => IpAddr::V6(Ipv6Addr::LOCALHOST),
        value => value,
    };
    SocketAddr::new(address, endpoint.port)
}
fn proxy(mut host: File) -> io::Result<()> {
    socket_timeout(&host, 5)?;
    let endpoint = read_target(&mut host)?;
    // Never becomes an arbitrary guest-network connection API.
    if !listening_endpoints()?.contains(&endpoint) {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "target is not an observed local listener"));
    }
    let mut tcp = TcpStream::connect_timeout(&local_target(&endpoint), Duration::from_secs(3))?;
    tcp.set_read_timeout(Some(Duration::from_secs(300)))?;
    tcp.set_write_timeout(Some(Duration::from_secs(300)))?;
    socket_timeout(&host, 300)?;
    let mut upload_host = host.try_clone()?;
    let mut upload_tcp = tcp.try_clone()?;
    let upload = thread::spawn(move || {
        let result = io::copy(&mut upload_host, &mut upload_tcp);
        let _ = upload_tcp.shutdown(if result.is_ok() { Shutdown::Write } else { Shutdown::Both });
    });
    let result = io::copy(&mut tcp, &mut host);
    let _ = tcp.shutdown(Shutdown::Both);
    unsafe { nix::libc::shutdown(host.as_raw_fd(), nix::libc::SHUT_RDWR); }
    let _ = upload.join();
    result.map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn parses_ipv4_ipv6_and_ignores_non_listeners() {
        assert_eq!(parse("0: 0100007F:1F40 00000000:0000 0A", false), Some(Endpoint { address: "127.0.0.1".parse().unwrap(), port: 8000 }));
        assert_eq!(parse("0: 00000000000000000000000001000000:01BB 0:0 0A", true), Some(Endpoint { address: "::1".parse().unwrap(), port: 443 }));
        assert!(parse("0: 0100007F:1F40 0:0 01", false).is_none());
        assert!(parse("garbage", true).is_none());
        assert!(parse("0: 00000000:0000 0:0 0A", false).is_none());
    }
    #[test] fn proxy_header_rejects_uris_and_truncation() {
        for address in ["intent:x", "example.com", "127.0.0.1/path", "::1%eth0"] {
            let mut encoded = vec![1, 0x1f, 0x40, address.len() as u8]; encoded.extend_from_slice(address.as_bytes());
            assert!(read_target(&mut encoded.as_slice()).is_err());
        }
        assert!(read_target(&mut [1, 0, 80, 9].as_slice()).is_err());
        assert_eq!(local_target(&Endpoint { address: "::".parse().unwrap(), port: 80 }), "[::1]:80".parse().unwrap());
    }
    #[test] fn reports_exact_v1_legacy_and_extended_envelope() {
        let temp = std::env::temp_dir().join(format!("term-port-{}", std::process::id()));
        let mut file = File::create(&temp).unwrap();
        send_endpoint(&mut file, 3, &Endpoint { address: "127.0.0.1".parse().unwrap(), port: 8000 }).unwrap();
        let bytes = std::fs::read(&temp).unwrap();
        assert_eq!(&bytes[0..8], &[0x44, 0x50, 0x54, 0x59, 0, 1, 4, 3]);
        assert_eq!(&bytes[24..], &[3, 0x1f, 0x40, 9, b'1', b'2', b'7', b'.', b'0', b'.', b'0', b'.', b'1', 0, 0]);
        std::fs::remove_file(temp).unwrap();
    }
}
