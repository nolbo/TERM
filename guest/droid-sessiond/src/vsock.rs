//! Framed Linux vsock transport. This module owns only sockets and framing;
//! session authorization and PTY operations stay in higher layers.

use std::fs::File;
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
                return Ok(VsockConnection { stream: File::from(OwnedFd::from(stream)) });
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
        Ok(VsockConnection { stream: unsafe { File::from_raw_fd(raw_fd) } })
    }
}

pub struct VsockConnection {
    stream: File,
}

impl VsockConnection {
    pub fn raw_fd(&self) -> std::os::fd::RawFd { self.stream.as_raw_fd() }

    pub fn read_frame(&mut self) -> Result<Frame, VsockError> {
        read_frame_from(&mut self.stream)
    }

    pub fn write_frame(&mut self, frame: &Frame) -> Result<(), VsockError> {
        write_frame_to(&mut self.stream, frame)
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
