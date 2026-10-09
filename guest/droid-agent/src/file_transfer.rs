//! Explicit copies only. No host paths, mounts, shell commands or guest-to-host requests.
use nix::libc;
use sha2::{Digest, Sha256};
use std::ffi::CString;
use std::fs::File;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::path::Path;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;

const MAGIC: u32 = 0x5446_5431;
const MAX: u64 = 64 * 1024 * 1024;
const ROOT: &str = "/home/droid/term-transfers";
static NEXT: AtomicU64 = AtomicU64::new(1);

fn invalid(message: &str) -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, message) }
fn cstr(name: &str) -> io::Result<CString> { CString::new(name).map_err(|_| invalid("NUL in path")) }
fn parts(path: &str) -> io::Result<Vec<&str>> {
    let values: Vec<_> = path.split('/').collect();
    if path.len() > 4096 || path.starts_with('/') || path.chars().any(char::is_control) || values.len() > 65 ||
        values.iter().any(|v| v.is_empty() || *v == "." || *v == ".." || v.len() > 255) { return Err(invalid("invalid relative path")); }
    Ok(values)
}
fn directory(parent: i32, name: &str, create: bool) -> io::Result<OwnedFd> {
    let name = cstr(name)?;
    if create && unsafe { libc::mkdirat(parent, name.as_ptr(), 0o700) } != 0 && io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST) { return Err(io::Error::last_os_error()); }
    let raw = unsafe { libc::openat(parent, name.as_ptr(), libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC) };
    if raw < 0 { return Err(io::Error::last_os_error()); }
    Ok(unsafe { OwnedFd::from_raw_fd(raw) })
}
fn open_root(root: &Path) -> io::Result<OwnedFd> {
    let mut fd = directory(libc::AT_FDCWD, "/", false)?;
    let text = root.to_str().ok_or_else(|| invalid("root encoding"))?;
    if !text.starts_with('/') { return Err(invalid("absolute root required")); }
    for name in parts(&text[1..])? { fd = directory(fd.as_raw_fd(), name, false)?; }
    Ok(fd)
}
fn regular(parent: i32, name: &str, create: bool) -> io::Result<File> {
    let name = cstr(name)?;
    let flags = libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK |
        if create { libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL } else { libc::O_RDONLY };
    let raw = unsafe { libc::openat(parent, name.as_ptr(), flags, 0o600) };
    if raw < 0 { return Err(io::Error::last_os_error()); }
    let file = unsafe { File::from_raw_fd(raw) };
    use std::os::unix::fs::MetadataExt;
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.nlink() != 1 { return Err(invalid("regular, unlinked files only")); }
    Ok(file)
}
fn body(input: &mut impl Read, output: &mut impl Write, size: u64) -> io::Result<[u8; 32]> {
    if size > MAX { return Err(invalid("file exceeds 64 MiB")); }
    let mut hash = Sha256::new();
    let mut remaining = size;
    let mut buffer = [0u8; 65536];
    while remaining > 0 {
        let count = (remaining as usize).min(buffer.len());
        input.read_exact(&mut buffer[..count])?;
        hash.update(&buffer[..count]); output.write_all(&buffer[..count])?;
        remaining -= count as u64;
    }
    Ok(hash.finalize().into())
}
fn receive(stream: &mut (impl Read + Write), parent: &OwnedFd, name: &str) -> io::Result<()> {
    let mut size = [0; 8]; stream.read_exact(&mut size)?;
    let size = u64::from_be_bytes(size);
    if size > MAX { return Err(invalid("file exceeds 64 MiB")); }
    let temporary = format!(".term-transfer-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed));
    let temporary_c = cstr(&temporary)?;
    let mut file = regular(parent.as_raw_fd(), &temporary, true)?;
    let result = (|| {
        let hash = body(stream, &mut file, size)?;
        let mut expected = [0; 32]; stream.read_exact(&mut expected)?;
        if hash != expected { return Err(invalid("SHA-256 mismatch")); }
        file.sync_all()?;
        let destination = cstr(name)?;
        let result = unsafe { libc::syscall(libc::SYS_renameat2, parent.as_raw_fd(), temporary_c.as_ptr(), parent.as_raw_fd(), destination.as_ptr(), 1) };
        if result != 0 { return Err(io::Error::last_os_error()); }
        if unsafe { libc::fsync(parent.as_raw_fd()) } != 0 { return Err(io::Error::last_os_error()); }
        stream.write_all(&[0])?; stream.write_all(&hash)?; stream.flush()
    })();
    if result.is_err() { unsafe { libc::unlinkat(parent.as_raw_fd(), temporary_c.as_ptr(), 0); } }
    result
}
fn send(stream: &mut (impl Read + Write), parent: &OwnedFd, name: &str) -> io::Result<()> {
    let mut file = regular(parent.as_raw_fd(), name, false)?;
    let size = file.metadata()?.len();
    if size > MAX { return Err(invalid("file exceeds 64 MiB")); }
    stream.write_all(&[0])?; stream.write_all(&size.to_be_bytes())?;
    let hash = body(&mut file, stream, size)?;
    let mut extra = [0];
    if file.read(&mut extra)? != 0 || file.metadata()?.len() != size { return Err(invalid("source changed during copy")); }
    stream.write_all(&hash)?; stream.flush()?;
    let mut ack = [1]; let mut confirmed = [0; 32];
    stream.read_exact(&mut ack)?; stream.read_exact(&mut confirmed)?;
    if ack != [0] || confirmed != hash { return Err(invalid("host did not verify transfer")); }
    Ok(())
}
fn handle(stream: &mut (impl Read + Write), root: &Path) -> io::Result<()> {
    stream.write_all(&MAGIC.to_be_bytes())?; stream.flush()?;
    let mut header = [0; 7]; stream.read_exact(&mut header)?;
    if u32::from_be_bytes(header[..4].try_into().unwrap()) != MAGIC || !matches!(header[4], 1 | 2) { return Err(invalid("unsupported file-transfer protocol")); }
    let length = u16::from_be_bytes(header[5..7].try_into().unwrap()) as usize;
    if !(1..=4096).contains(&length) { return Err(invalid("path size")); }
    let mut path = vec![0; length]; stream.read_exact(&mut path)?;
    let text = std::str::from_utf8(&path).map_err(|_| invalid("path encoding"))?;
    let components = parts(text)?;
    let mut parent = open_root(root)?;
    for part in &components[..components.len()-1] { parent = directory(parent.as_raw_fd(), part, header[4] == 1)?; }
    if header[4] == 1 { receive(stream, &parent, components.last().unwrap()) } else { send(stream, &parent, components.last().unwrap()) }
}
pub fn serve() -> io::Result<()> {
    // The dedicated systemd invocation runs as droid. Never run this endpoint as guest root.
    if unsafe { libc::geteuid() } == 0 { return Err(invalid("file transfer requires the droid user")); }
    let home = open_root(Path::new("/home/droid"))?;
    directory(home.as_raw_fd(), "term-transfers", true)?;
    let listener = super::bind_vsock(40_505)?;
    let active = Arc::new(AtomicUsize::new(0));
    loop {
        let mut stream = match super::accept_host(&listener) { Ok(value) => value, Err(_) => continue };
        if active.fetch_add(1, Ordering::SeqCst) >= 2 { active.fetch_sub(1, Ordering::SeqCst); continue; }
        let active = active.clone();
        std::thread::spawn(move || {
            let timeout = libc::timeval { tv_sec: 30, tv_usec: 0 };
            for option in [libc::SO_RCVTIMEO, libc::SO_SNDTIMEO] {
                unsafe { libc::setsockopt(stream.as_raw_fd(), libc::SOL_SOCKET, option, &timeout as *const _ as _, std::mem::size_of_val(&timeout) as _); }
            }
            if handle(&mut stream, Path::new(ROOT)).is_err() { let _ = stream.write_all(&[1]); }
            active.fetch_sub(1, Ordering::SeqCst);
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    fn root() -> std::path::PathBuf {
        let root = std::env::temp_dir().join(format!("term-file-transfer-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::SeqCst)));
        std::fs::create_dir(&root).unwrap(); root
    }
    #[test] fn reject_path_escapes() {
        for path in ["", "/etc/passwd", "../x", "a/../b", "a//b", "a/./b", "a\0b"] { assert!(parts(path).is_err(), "{path}"); }
        assert!(parts("project/src/file.txt").is_ok());
        assert!(parts(&vec!["a"; 66].join("/")).is_err());
    }
    #[test] fn received_file_is_verified_and_never_replaces_data() {
        let root = root(); let parent = open_root(&root).unwrap();
        let bytes = b"hello transfer";
        let mut wire = Vec::new(); wire.extend_from_slice(&(bytes.len() as u64).to_be_bytes()); wire.extend_from_slice(bytes); wire.extend_from_slice(&Sha256::digest(bytes));
        receive(&mut Cursor::new(wire.clone()), &parent, "result").unwrap();
        assert_eq!(std::fs::read(root.join("result")).unwrap(), bytes);
        assert!(receive(&mut Cursor::new(wire), &parent, "result").is_err());
        assert_eq!(std::fs::read(root.join("result")).unwrap(), bytes);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn corrupt_and_cancelled_transfers_leave_no_destination() {
        let root = root(); let parent = open_root(&root).unwrap();
        for wire in [vec![0, 0, 0, 0, 0, 0, 0, 5, 1], [5u64.to_be_bytes().as_slice(), b"hello", &[0; 32]].concat()] {
            assert!(receive(&mut Cursor::new(wire), &parent, "result").is_err());
            assert!(!root.join("result").exists());
            assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn symlink_parent_and_leaf_are_rejected() {
        let root = root(); let parent = open_root(&root).unwrap();
        std::os::unix::fs::symlink("/tmp", root.join("escape")).unwrap();
        std::os::unix::fs::symlink("/etc/passwd", root.join("secret")).unwrap();
        assert!(directory(parent.as_raw_fd(), "escape", false).is_err());
        assert!(regular(parent.as_raw_fd(), "secret", false).is_err());
        std::fs::write(root.join("original"), b"keep").unwrap();
        std::fs::hard_link(root.join("original"), root.join("alias")).unwrap();
        assert!(regular(parent.as_raw_fd(), "alias", false).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn framed_requests_confine_files_and_enforce_size_before_publication() {
        struct Wire { input: Cursor<Vec<u8>>, output: Vec<u8> }
        impl Read for Wire { fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> { self.input.read(bytes) } }
        impl Write for Wire {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> { self.output.write(bytes) }
            fn flush(&mut self) -> io::Result<()> { Ok(()) }
        }
        let root = root();
        let payload = vec![42u8; 200_000];
        let path = b"Project/sub/file";
        let header = [&MAGIC.to_be_bytes()[..], &[1], &(path.len() as u16).to_be_bytes(), path].concat();
        let mut wire = Wire { input: Cursor::new([&header[..], &(payload.len() as u64).to_be_bytes(), &payload, &Sha256::digest(&payload)].concat()), output: Vec::new() };
        handle(&mut wire, &root).unwrap();
        assert_eq!(std::fs::read(root.join("Project/sub/file")).unwrap(), payload);
        assert_eq!(wire.output, [&MAGIC.to_be_bytes()[..], &[0], &Sha256::digest(&payload)].concat());
        let path = b"Project/sub/oversized";
        let mut wire = Wire { input: Cursor::new([&MAGIC.to_be_bytes()[..], &[1], &(path.len() as u16).to_be_bytes(), path, &(MAX+1).to_be_bytes()].concat()), output: Vec::new() };
        assert!(handle(&mut wire, &root).is_err());
        assert!(!root.join("Project/sub/oversized").exists());
        assert_eq!(std::fs::read_dir(root.join("Project/sub")).unwrap().count(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test] fn exported_file_requires_a_verified_receipt() {
        struct Wire { input: Cursor<Vec<u8>>, output: Vec<u8> }
        impl Read for Wire { fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> { self.input.read(bytes) } }
        impl Write for Wire {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> { self.output.write(bytes) }
            fn flush(&mut self) -> io::Result<()> { Ok(()) }
        }
        let root = root(); let parent = open_root(&root).unwrap();
        let bytes = b"guest export";
        std::fs::write(root.join("source"), bytes).unwrap();
        let hash = Sha256::digest(bytes);
        let mut wire = Wire { input: Cursor::new([&[0][..], &hash].concat()), output: Vec::new() };
        send(&mut wire, &parent, "source").unwrap();
        assert_eq!(wire.output, [&[0][..], &(bytes.len() as u64).to_be_bytes(), bytes, &hash].concat());
        let mut bad = Wire { input: Cursor::new(vec![0; 33]), output: Vec::new() };
        assert!(send(&mut bad, &parent, "source").is_err());
        assert_eq!(std::fs::read(root.join("source")).unwrap(), bytes);
        std::fs::remove_dir_all(root).unwrap();
    }
}
