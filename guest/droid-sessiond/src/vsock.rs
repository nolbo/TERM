//! Framed Linux vsock transport. This module owns only sockets and framing;
//! session authorization and PTY operations stay in higher layers.

use std::fs::File;
use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::mem::size_of;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::net::UnixListener;
use std::os::unix::fs::PermissionsExt;

use nix::errno::Errno;

use crate::protocol::{Frame, ProtocolError, HEADER_SIZE, MAX_PAYLOAD_BYTES};

pub const SESSIOND_PORT: u32 = 40_500;
const VMADDR_CID_ANY: u32 = u32::MAX;
const VMADDR_CID_HOST: u32 = 2;

/// Linux `sockaddr_vm`, kept local because its libc exposure varies by target.
#[repr(C)]
struct SockaddrVm {
    family: nix::libc::sa_family_t,
    reserved: u16,
    port: u32,
    cid: u32,
    zero: [u8; 4],
}

#[derive(Debug)]
pub enum VsockError {
    Socket(Errno),
    Io(io::Error),
    Protocol(ProtocolError),
    InvalidFrameLength,
    UntrustedPeer,
}

impl From<Errno> for VsockError {
    fn from(error: Errno) -> Self { Self::Socket(error) }
}

impl From<io::Error> for VsockError {
    fn from(error: io::Error) -> Self { Self::Io(error) }
}

impl From<ProtocolError> for VsockError {
    fn from(error: ProtocolError) -> Self { Self::Protocol(error) }
}

pub struct VsockListener {
    socket: OwnedFd,
    local: Option<UnixListener>,
}

impl VsockListener {
    pub fn bind(port: u32) -> Result<Self, VsockError> {
        let raw_fd = unsafe {
            nix::libc::socket(
                nix::libc::AF_VSOCK,
                nix::libc::SOCK_STREAM | nix::libc::SOCK_CLOEXEC,
                0,
            )
        };
        if raw_fd < 0 { return Err(VsockError::Socket(Errno::last())); }
        let socket = unsafe { OwnedFd::from_raw_fd(raw_fd) };
        let address = SockaddrVm {
            family: nix::libc::AF_VSOCK as nix::libc::sa_family_t,
            reserved: 0,
            port,
            cid: VMADDR_CID_ANY,
            zero: [0; 4],
        };
        let bind_result = unsafe {
            nix::libc::bind(
                socket.as_raw_fd(),
                &address as *const SockaddrVm as *const nix::libc::sockaddr,
                size_of::<SockaddrVm>() as nix::libc::socklen_t,
            )
        };
        if bind_result < 0 { return Err(VsockError::Socket(Errno::last())); }
        if unsafe { nix::libc::listen(socket.as_raw_fd(), 16) } < 0 {
            return Err(VsockError::Socket(Errno::last()));
        }
        let local = if let Some(path) = std::env::var_os("TERM_SESSION_RELAY") {
            match std::fs::remove_file(&path) {
                Ok(()) => (),
                Err(error) if error.kind() == io::ErrorKind::NotFound => (),
                Err(error) => return Err(error.into()),
            }
            let listener = UnixListener::bind(&path)?;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
            Some(listener)
        } else { None };
        Ok(Self { socket, local })
    }

    pub fn accept(&self) -> Result<VsockConnection, VsockError> {
        if let Some(local) = &self.local {
            let mut descriptors = [
                nix::libc::pollfd { fd: self.socket.as_raw_fd(), events: nix::libc::POLLIN, revents: 0 },
                nix::libc::pollfd { fd: local.as_raw_fd(), events: nix::libc::POLLIN, revents: 0 },
            ];
            if unsafe { nix::libc::poll(descriptors.as_mut_ptr(), 2, -1) } < 0 {
                return Err(Errno::last().into());
            }
            if descriptors[1].revents & nix::libc::POLLIN != 0 {
                let (stream, _) = local.accept()?;
                let mut credentials = nix::libc::ucred { pid: 0, uid: u32::MAX, gid: u32::MAX };
                let mut size = size_of::<nix::libc::ucred>() as nix::libc::socklen_t;
                if unsafe { nix::libc::getsockopt(stream.as_raw_fd(), nix::libc::SOL_SOCKET,
                    nix::libc::SO_PEERCRED, &mut credentials as *mut _ as *mut _, &mut size) } < 0 {
                    return Err(Errno::last().into());
                }
                if size as usize != size_of::<nix::libc::ucred>() || credentials.uid != 0 {
                    return Err(VsockError::UntrustedPeer);
                }
                return VsockConnection::new(File::from(OwnedFd::from(stream)));
            }
        }
        let mut address = SockaddrVm {
            family: 0, reserved: 0, port: 0, cid: 0, zero: [0; 4],
        };
        let mut address_length = size_of::<SockaddrVm>() as nix::libc::socklen_t;
        let raw_fd = unsafe {
            nix::libc::accept4(
                self.socket.as_raw_fd(),
                &mut address as *mut SockaddrVm as *mut nix::libc::sockaddr,
                &mut address_length,
                nix::libc::SOCK_CLOEXEC,
            )
        };
        if raw_fd < 0 { return Err(VsockError::Socket(Errno::last())); }
        if address_length as usize != size_of::<SockaddrVm>() || address.cid != VMADDR_CID_HOST {
            unsafe { nix::libc::close(raw_fd) };
            return Err(VsockError::UntrustedPeer);
        }
        VsockConnection::new(unsafe { File::from_raw_fd(raw_fd) })
    }
}

const OUTPUT_LIMIT: usize = 16 * 1024 * 1024;
pub struct VsockConnection {
    stream: File,
    incoming: Vec<u8>,
    outgoing: VecDeque<Vec<u8>>,
    written: usize,
    queued: usize,
    nonblocking: bool,
}

impl VsockConnection {
    pub fn new(stream: File) -> Result<Self, VsockError> {
        Ok(Self { stream, incoming: Vec::new(), outgoing: VecDeque::new(), written: 0, queued: 0, nonblocking: false })
    }
    pub fn enable_nonblocking(&mut self) -> Result<(), VsockError> {
        let fd = self.raw_fd();
        let flags = unsafe { nix::libc::fcntl(fd, nix::libc::F_GETFL) };
        if flags < 0 || unsafe { nix::libc::fcntl(fd, nix::libc::F_SETFL, flags | nix::libc::O_NONBLOCK) } < 0 {
            return Err(Errno::last().into());
        }
        self.nonblocking = true;
        Ok(())
    }
    pub fn read_frame(&mut self) -> Result<Frame, VsockError> { read_frame_from(&mut self.stream) }
    pub fn raw_fd(&self) -> std::os::fd::RawFd { self.stream.as_raw_fd() }
    pub fn wants_write(&self) -> bool { !self.outgoing.is_empty() }
    pub fn has_pty_capacity(&self) -> bool { self.queued <= OUTPUT_LIMIT - 9 * 1024 * 1024 }
    pub fn has_capacity(&self) -> bool { self.queued <= OUTPUT_LIMIT - MAX_PAYLOAD_BYTES - HEADER_SIZE }

    /// A fragmented host frame never blocks draining other PTYs.
    pub fn read_frame_ready(&mut self) -> Result<Option<Frame>, VsockError> {
        loop {
            let needed = if self.incoming.len() < HEADER_SIZE { HEADER_SIZE } else {
                let length = u32::from_be_bytes(self.incoming[20..24].try_into().unwrap()) as usize;
                if length > MAX_PAYLOAD_BYTES { return Err(VsockError::InvalidFrameLength); }
                HEADER_SIZE + length
            };
            if self.incoming.len() == needed && needed >= HEADER_SIZE {
                let length = u32::from_be_bytes(self.incoming[20..24].try_into().unwrap()) as usize;
                if length > MAX_PAYLOAD_BYTES { return Err(VsockError::InvalidFrameLength); }
                if needed == HEADER_SIZE && length != 0 { continue; }
                let result = Frame::decode(&self.incoming).map_err(VsockError::from);
                self.incoming.clear();
                if self.incoming.capacity() > 64 * 1024 { self.incoming = Vec::new(); }
                return result.map(Some);
            }
            let mut buffer = [0u8; 8192];
            let count = buffer.len().min(needed - self.incoming.len());
            match self.stream.read(&mut buffer[..count]) {
                Ok(0) => return Err(io::Error::from(io::ErrorKind::UnexpectedEof).into()),
                Ok(count) => self.incoming.extend_from_slice(&buffer[..count]),
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(None),
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error.into()),
            }
        }
    }
    pub fn write_frame(&mut self, frame: &Frame) -> Result<(), VsockError> {
        if !self.nonblocking { return write_frame_to(&mut self.stream, frame); }
        let bytes = frame.encode()?;
        if bytes.len() > OUTPUT_LIMIT - self.queued {
            return Err(io::Error::other("session output queue is full").into());
        }
        self.queued += bytes.len();
        self.outgoing.push_back(bytes);
        Ok(())
    }
    pub fn flush_output(&mut self) -> Result<(), VsockError> {
        let mut budget = 256 * 1024;
        while let Some(bytes) = self.outgoing.front() {
            let end = bytes.len().min(self.written + budget);
            match self.stream.write(&bytes[self.written..end]) {
                Ok(0) => return Err(io::Error::from(io::ErrorKind::WriteZero).into()),
                Ok(count) => {
                    self.written += count; self.queued -= count; budget -= count;
                    if self.written == bytes.len() { self.outgoing.pop_front(); self.written = 0; }
                    if budget == 0 { break; }
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error.into()),
            }
        }
        Ok(())
    }
}

pub fn read_frame_from(reader: &mut impl Read) -> Result<Frame, VsockError> {
    let mut header = [0_u8; HEADER_SIZE];
    reader.read_exact(&mut header)?;
    let payload_size = u32::from_be_bytes(header[20..24].try_into().expect("fixed slice")) as usize;
    if payload_size > MAX_PAYLOAD_BYTES { return Err(VsockError::InvalidFrameLength); }
    let mut complete_frame = Vec::with_capacity(HEADER_SIZE + payload_size);
    complete_frame.extend_from_slice(&header);
    complete_frame.resize(HEADER_SIZE + payload_size, 0);
    reader.read_exact(&mut complete_frame[HEADER_SIZE..])?;
    Frame::decode(&complete_frame).map_err(VsockError::from)
}

pub fn write_frame_to(writer: &mut impl Write, frame: &Frame) -> Result<(), VsockError> {
    writer.write_all(&frame.encode()?)?;
    writer.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;
    use crate::protocol::{FrameKind, Service};

    #[test]
    fn nonblocking_output_keeps_partial_write_offsets_and_frame_order() {
        use std::os::unix::net::UnixStream;
        use std::time::{Duration, Instant};
        let (socket, mut peer) = UnixStream::pair().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let size: nix::libc::c_int = 4096;
        unsafe { nix::libc::setsockopt(socket.as_raw_fd(), nix::libc::SOL_SOCKET, nix::libc::SO_SNDBUF,
            &size as *const _ as *const _, size_of::<nix::libc::c_int>() as _); }
        let mut connection = VsockConnection::new(File::from(OwnedFd::from(socket))).unwrap();
        connection.enable_nonblocking().unwrap();
        let frame = Frame { service: crate::protocol::Service::Session,
            kind: crate::protocol::FrameKind::Stream, flags: 0, request_id: 1, payload: vec![7; 100_000] };
        let mut second = frame.clone(); second.request_id = 2; second.payload = vec![9; 65_537];
        connection.write_frame(&frame).unwrap(); connection.write_frame(&second).unwrap();
        connection.flush_output().unwrap();
        assert!(connection.wants_write(), "the unread socket must backpressure without blocking");
        let reader = std::thread::spawn(move || (read_frame_from(&mut peer).unwrap(), read_frame_from(&mut peer).unwrap()));
        let deadline = Instant::now() + Duration::from_secs(3);
        while connection.wants_write() {
            assert!(Instant::now() < deadline);
            connection.flush_output().unwrap();
            std::thread::sleep(Duration::from_millis(1));
        }
        assert_eq!(reader.join().unwrap(), (frame, second));
    }
    #[test]
    fn oversized_nonblocking_frame_is_rejected_before_payload_allocation() {
        use std::os::unix::net::UnixStream;
        let (socket, mut peer) = UnixStream::pair().unwrap();
        let mut connection = VsockConnection::new(File::from(OwnedFd::from(socket))).unwrap();
        connection.enable_nonblocking().unwrap();
        let frame = Frame { service: crate::protocol::Service::Session,
            kind: crate::protocol::FrameKind::Request, flags: 0, request_id: 1, payload: vec![] };
        let mut header = frame.encode().unwrap();
        header[20..24].copy_from_slice(&((MAX_PAYLOAD_BYTES + 1) as u32).to_be_bytes());
        peer.write_all(&header).unwrap();
        assert!(matches!(connection.read_frame_ready(), Err(VsockError::InvalidFrameLength)));
        assert!(connection.incoming.capacity() < 1024);
    }

    #[test]
    fn reads_a_single_complete_frame_without_transport_specific_state() {
        let frame = Frame {
            service: Service::Session,
            kind: FrameKind::Request,
            flags: 0,
            request_id: 8,
            payload: b"session request".to_vec(),
        };
        let mut wire = Cursor::new(Vec::new());
        write_frame_to(&mut wire, &frame).unwrap();
        wire.set_position(0);
        assert_eq!(read_frame_from(&mut wire).unwrap(), frame);
    }
}
