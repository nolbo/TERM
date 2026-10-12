//! A trusted APK tool receives ONE anonymous connector descriptor, not a USB FD.
//! Each connection requests a fresh socketpair from the Android-owned mux owner.
//! There is deliberately no pathname/TCP/discovery/default-usbmux fallback.
use std::{future::Future, io, os::fd::{AsRawFd, FromRawFd, OwnedFd}, pin::Pin, sync::Arc, time::Duration};
use idevice::{Idevice, IdeviceError, provider::IdeviceProvider, pairing_file::PairingFile, usbmuxd::UsbmuxdConnection};

/// Connect only to the randomized abstract host IPC address, then receive one connector.
/// The host authenticates the peer UID and random token before sending SCM_RIGHTS.
pub fn authenticate_host(address: &str, token: &str) -> io::Result<(OwnedFd, OwnedFd)> {
    if address.is_empty() || address.len() > 100 || token.len() != 37 || !token.ends_with('\n') { return Err(io::Error::other("invalid host handshake")); }
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 { return Err(io::Error::last_os_error()); }
        let owner = OwnedFd::from_raw_fd(fd);
        let deadline = libc::timeval { tv_sec: 5, tv_usec: 0 };
        for option in [libc::SO_RCVTIMEO, libc::SO_SNDTIMEO] {
            if libc::setsockopt(fd, libc::SOL_SOCKET, option, &deadline as *const _ as _, std::mem::size_of_val(&deadline) as _) != 0 {
                return Err(io::Error::last_os_error());
            }
        }
        let mut sockaddr: libc::sockaddr_un = std::mem::zeroed(); sockaddr.sun_family = libc::AF_UNIX as _;
        for (index, byte) in address.bytes().enumerate() { sockaddr.sun_path[index + 1] = byte as _; }
        let length = std::mem::offset_of!(libc::sockaddr_un, sun_path) + address.len() + 1;
        if libc::connect(fd, &sockaddr as *const _ as _, length as _) != 0 { return Err(io::Error::other("host connect failed")); }
        let mut credentials: libc::ucred = std::mem::zeroed(); let mut size = std::mem::size_of_val(&credentials) as libc::socklen_t;
        if libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_PEERCRED, &mut credentials as *mut _ as _, &mut size) != 0 || credentials.uid != libc::getuid() { return Err(io::Error::other("host UID mismatch")); }
        if libc::send(fd, token.as_ptr() as _, token.len(), libc::MSG_NOSIGNAL) != token.len() as isize { return Err(io::Error::other("token write failed")); }
        Ok((owner, receive_fd(fd, b'M')?))
    }
}

#[derive(Debug)]
struct ConnectorState { socket: tokio::net::UnixStream, poisoned: bool }
#[derive(Debug, Clone)]
pub struct Connector(Arc<tokio::sync::Mutex<ConnectorState>>);
impl Connector {
    pub fn new(fd: OwnedFd) -> io::Result<Self> {
        let socket = std::os::unix::net::UnixStream::from(fd);
        socket.set_nonblocking(true)?;
        Ok(Self(Arc::new(tokio::sync::Mutex::new(ConnectorState {
            socket: tokio::net::UnixStream::from_std(socket)?, poisoned: false,
        }))))
    }
    pub async fn connection(&self) -> Result<UsbmuxdConnection, IdeviceError> {
        Ok(UsbmuxdConnection::new(Box::new(self.stream().await?), 0))
    }
    /// Fresh trusted stream for bounded usbmux operations whose upstream API loses errno.
    pub async fn stream(&self) -> Result<tokio::net::UnixStream, IdeviceError> {
        self.stream_with_deadline(Duration::from_secs(5)).await
    }
    async fn stream_with_deadline(&self, deadline: Duration) -> Result<tokio::net::UnixStream, IdeviceError> {
        let fd = tokio::time::timeout(deadline, async {
            let mut guard = self.0.lock().await;
            if guard.poisoned { return Err(io::Error::other("connector closed after interrupted request")); }
            // Cancellation, timeout, or malformed reply poisons this connector.
            // Never consume a late descriptor as the reply to another request.
            guard.poisoned = true;
            loop {
                guard.socket.writable().await?;
                match guard.socket.try_write(b"M") {
                    Ok(1) => break,
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
                    Err(e) => return Err(e),
                    _ => return Err(io::Error::other("connector write failed")),
                }
            }
            let fd = loop {
                guard.socket.readable().await?;
                match guard.socket.try_io(tokio::io::Interest::READABLE,
                    || receive_fd_flags(guard.socket.as_raw_fd(), b'M', libc::MSG_DONTWAIT)) {
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => continue,
                    result => break result?,
                }
            };
            guard.poisoned = false;
            Ok::<_, io::Error>(fd)
        }).await.map_err(|_| IdeviceError::Timeout)??;
        let stream = std::os::unix::net::UnixStream::from(fd); stream.set_nonblocking(true)?;
        Ok(tokio::net::UnixStream::from_std(stream)?)
    }
}

/// Validate exactly one CLOEXEC descriptor; close all received descriptors on rejection.
pub fn receive_fd(socket: i32, expected: u8) -> io::Result<OwnedFd> {
    receive_fd_flags(socket, expected, 0)
}
fn receive_fd_flags(socket: i32, expected: u8, flags: i32) -> io::Result<OwnedFd> {
    let mut marker = 0u8;
    let mut iov = libc::iovec { iov_base: &mut marker as *mut _ as _, iov_len: 1 };
    let mut control = [0usize; 8]; // ARM64 cmsghdr alignment
    let mut message: libc::msghdr = unsafe { std::mem::zeroed() };
    message.msg_iov = &mut iov; message.msg_iovlen = 1;
    message.msg_control = control.as_mut_ptr() as _; message.msg_controllen = std::mem::size_of_val(&control);
    let count = unsafe { libc::recvmsg(socket, &mut message, libc::MSG_CMSG_CLOEXEC | flags) };
    if count < 0 { return Err(io::Error::last_os_error()); }
    let mut descriptors = Vec::new(); let mut valid = true;
    unsafe {
        let mut cmsg = libc::CMSG_FIRSTHDR(&message);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let Some(bytes) = ((*cmsg).cmsg_len as usize).checked_sub(libc::CMSG_LEN(0) as usize) else { valid = false; break; };
                valid &= bytes % std::mem::size_of::<i32>() == 0;
                for index in 0..bytes / std::mem::size_of::<i32>() { descriptors.push(OwnedFd::from_raw_fd(*(libc::CMSG_DATA(cmsg) as *const i32).add(index))); }
            } else { valid = false; }
            cmsg = libc::CMSG_NXTHDR(&message, cmsg);
        }
    }
    if !valid || count != 1 || marker != expected || message.msg_flags & (libc::MSG_CTRUNC | libc::MSG_TRUNC) != 0 || descriptors.len() != 1 { return Err(io::Error::other("invalid descriptor response")); }
    Ok(descriptors.pop().unwrap())
}

#[derive(Clone)]
pub struct PrivateProvider { pub connector: Connector, pub device_id: u32, pub udid: String }
impl std::fmt::Debug for PrivateProvider {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { f.write_str("PrivateProvider") }
}
impl IdeviceProvider for PrivateProvider {
    fn connect(&self, port: u16) -> Pin<Box<dyn Future<Output = Result<Idevice, IdeviceError>> + Send>> {
        let this = self.clone();
        Box::pin(async move { this.connector.connection().await?.connect_to_device(this.device_id, port, "terminal-iphone-tool").await })
    }
    fn label(&self) -> &str { "terminal-iphone-tool" }
    fn get_pairing_file(&self) -> Pin<Box<dyn Future<Output = Result<PairingFile, IdeviceError>> + Send>> {
        let this = self.clone(); Box::pin(async move { this.connector.connection().await?.get_pair_record(&this.udid).await })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{io::Write, os::unix::net::UnixStream};
    #[tokio::test]
    async fn deadline_and_cancellation_poison_connector_without_replay() {
        use tokio::io::AsyncReadExt;
        for cancel in [false, true] {
            let (host, worker) = UnixStream::pair().unwrap();
            host.set_nonblocking(true).unwrap();
            let mut host = tokio::net::UnixStream::from_std(host).unwrap();
            let connector = Connector::new(worker.into()).unwrap();
            let task_connector = connector.clone();
            let task = tokio::spawn(async move { task_connector.stream_with_deadline(Duration::from_millis(100)).await });
            let mut marker = [0];
            tokio::time::timeout(Duration::from_secs(2), host.read_exact(&mut marker)).await.unwrap().unwrap();
            assert_eq!(&marker, b"M");
            if cancel { task.abort(); assert!(task.await.unwrap_err().is_cancelled()); }
            else { assert!(matches!(task.await.unwrap(), Err(IdeviceError::Timeout))); }
            // A second request fails immediately and never emits another marker.
            assert!(tokio::time::timeout(Duration::from_secs(1), connector.stream()).await.unwrap().is_err());
            assert!(tokio::time::timeout(Duration::from_millis(20), host.read_exact(&mut marker)).await.is_err());
        }
    }
    #[test]
    fn rejects_missing_descriptor_bad_marker_and_invalid_handshake() {
        let (mut host, worker) = UnixStream::pair().unwrap();
        host.write_all(b"X").unwrap(); assert!(receive_fd(worker.as_raw_fd(), b'M').is_err());
        host.write_all(b"M").unwrap(); assert!(receive_fd(worker.as_raw_fd(), b'M').is_err());
        assert!(authenticate_host("", "not a token").is_err());
    }
    #[test]
    fn accepts_only_one_cloexec_descriptor_and_closes_rejected_descriptors() {
        let (host, worker) = UnixStream::pair().unwrap();
        let (first, _peer) = UnixStream::pair().unwrap();
        fn send(socket: i32, fds: &[i32], marker: u8) {
            let mut iov = libc::iovec { iov_base: &marker as *const _ as _, iov_len: 1 };
            let mut control = [0usize; 8];
            let mut message: libc::msghdr = unsafe { std::mem::zeroed() };
            message.msg_iov = &mut iov; message.msg_iovlen = 1;
            message.msg_control = control.as_mut_ptr() as _;
            unsafe {
                message.msg_controllen = libc::CMSG_SPACE(std::mem::size_of_val(fds) as _) as _;
                let cmsg = libc::CMSG_FIRSTHDR(&message); (*cmsg).cmsg_level = libc::SOL_SOCKET; (*cmsg).cmsg_type = libc::SCM_RIGHTS;
                (*cmsg).cmsg_len = libc::CMSG_LEN(std::mem::size_of_val(fds) as _) as _;
                std::ptr::copy_nonoverlapping(fds.as_ptr(), libc::CMSG_DATA(cmsg) as *mut i32, fds.len());
                assert_eq!(libc::sendmsg(socket, &message, libc::MSG_NOSIGNAL), 1);
            }
        }
        send(host.as_raw_fd(), &[first.as_raw_fd()], b'M');
        let received = receive_fd(worker.as_raw_fd(), b'M').unwrap();
        assert!(unsafe { libc::fcntl(received.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC != 0);
        send(host.as_raw_fd(), &[first.as_raw_fd(), first.as_raw_fd()], b'M');
        assert!(receive_fd(worker.as_raw_fd(), b'M').is_err());
        send(host.as_raw_fd(), &[first.as_raw_fd()], b'X'); assert!(receive_fd(worker.as_raw_fd(), b'M').is_err());
    }
}
