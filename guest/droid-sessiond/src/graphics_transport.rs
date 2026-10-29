//! Convert kitty local media into bounded direct-transfer APCs inside the guest.
//! No guest pathname, shared-memory handle or descriptor crosses vsock.

use std::collections::BTreeMap;
use std::ffi::CString;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Component, Path};

const MAX_BYTES: usize = 6 * 1024 * 1024;
const FRAME_BYTES: usize = 32 * 1024;
const MEDIA_BLOCK_BYTES: usize = 3072;
const MEDIA_BLOCKS_PER_TURN: usize = 7;
const MEDIA_TURN_BYTES: usize = MEDIA_BLOCK_BYTES * MEDIA_BLOCKS_PER_TURN;
const CANCEL_TRANSFER: &[u8] = b"\x1b_\x18";
const BASE64: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

#[derive(Default)]
pub struct Output { pub bytes: Vec<Vec<u8>>, pub replies: Vec<Vec<u8>> }

#[derive(Default)]
pub struct GraphicsTransport {
    state: u8,
    apc: Vec<u8>,
    osc: bool,
    chunked: bool,
    utf8_remaining: u8,
    media: Option<MediaTransfer>,
}

impl GraphicsTransport {
    pub fn accept_prefix(&mut self, input: &[u8]) -> (usize, Output) {
        self.accept_prefix_inner(input, 64 * 1024, read_media, true)
    }

    #[cfg(test)]
    fn accept_with<F>(&mut self, input: &[u8], read: F) -> Output
    where F: FnMut(char, &str, u64, u64) -> Result<Vec<u8>, &'static str> {
        self.accept_prefix_with(input, usize::MAX, read)
            .1
    }

    #[cfg(test)]
    fn accept_prefix_with<F>(&mut self, input: &[u8], limit: usize, mut read: F) -> (usize, Output)
    where F: FnMut(char, &str, u64, u64) -> Result<Vec<u8>, &'static str> {
        let mut used = 0;
        let mut output = Output::default();
        while used < input.len() || self.has_pending_media() {
            let (count, next) = self.accept_prefix_inner(&input[used..], limit,
                |m, n, o, s| read(m, n, o, s).map(Media::memory), true);
            used += count; output.bytes.extend(next.bytes); output.replies.extend(next.replies);
            if !self.has_pending_media() && output.bytes.iter().map(Vec::len).sum::<usize>() >= limit { break; }
        }
        (used, output)
    }

    fn accept_prefix_inner<F>(&mut self, input: &[u8], limit: usize, mut read: F, fast: bool) -> (usize, Output)
    where F: FnMut(char, &str, u64, u64) -> Result<Media, &'static str> {
        if self.has_pending_media() { return (0, self.advance_media()); }
        let mut result = Output::default();
        // UTF-8 text and complete CSI sequences cannot introduce graphics media.
        // Carry split UTF-8 continuations; malformed text and strings keep the full parser.
        if fast && self.state == 0 {
            if let Some(remaining) = plain_terminal_text(input, self.utf8_remaining) {
                self.utf8_remaining = remaining;
                append(&mut result, input);
                return (input.len(), result);
            }
        }
        let mut normal = Vec::new();
        let mut consumed = 0;
        for (index, &b) in input.iter().enumerate() {
            consumed = index + 1;
            match self.state {
                0 => {
                    if self.utf8_remaining > 0 && b & 0xc0 == 0x80 {
                        normal.push(b); self.utf8_remaining -= 1; continue;
                    }
                    self.utf8_remaining = match b { 0xc2..=0xdf => 1, 0xe0..=0xef => 2, 0xf0..=0xf4 => 3, _ => 0 };
                    match b {
                        27 => self.state = 1,
                        0x9f => { self.apc.clear(); self.apc.extend_from_slice(b"\x1b_"); self.state = 2; }
                        _ => {
                            normal.push(b);
                            if matches!(b, 0x90 | 0x9d | 0x98 | 0x9e) { self.state = 4; self.osc = b == 0x9d; }
                        }
                    }
                }
                1 => {
                    if b == b'_' { self.apc.clear(); self.apc.extend_from_slice(b"\x1b_"); self.state = 2; }
                    else {
                        normal.push(27);
                        if b == 27 { continue; }
                        normal.push(b);
                        self.state = if matches!(b, b'P' | b']' | b'X' | b'^') { 4 } else { 0 };
                        self.osc = b == b']';
                    }
                }
                2 | 3 => {
                    self.apc.push(b);
                    if b == 24 || b == 26 {
                        append(&mut result, &normal); normal.clear(); append(&mut result, &self.apc);
                        self.apc.clear(); self.state = 0; self.chunked = false;
                    } else if self.state == 3 && b == b'\\' || b == 0x9c {
                        append(&mut result, &normal); normal.clear();
                        self.process(&mut result, &mut read);
                        self.apc.clear(); self.state = 0;
                        if self.has_pending_media() || result.bytes.iter().map(Vec::len).sum::<usize>() >= limit { break; }
                    } else if self.apc.len() > 5124 {
                        append(&mut result, &normal); normal.clear(); append(&mut result, &self.apc);
                        self.apc.clear(); self.state = if b == 27 { 7 } else { 6 }; self.chunked = false;
                    } else { self.state = if b == 27 { 3 } else { 2 }; }
                }
                4..=7 => {
                    normal.push(b);
                    let other = self.state <= 5;
                    if matches!(b, 24 | 26 | 0x9c) || (self.state == 5 || self.state == 7) && b == b'\\' || other && self.osc && b == 7 {
                        self.state = 0;
                    } else { self.state = if b == 27 { if other { 5 } else { 7 } } else if other { 4 } else { 6 }; }
                }
                _ => unreachable!(),
            }
            if normal.len() >= FRAME_BYTES { append(&mut result, &normal); normal.clear(); }
        }
        append(&mut result, &normal);
        (consumed, result)
    }

    pub fn has_pending_media(&self) -> bool { self.media.is_some() }

    pub fn advance_media(&mut self) -> Output {
        let Some(mut transfer) = self.media.take() else { return Output::default(); };
        match transfer.step() {
            Ok((done, bytes)) => {
                if !done { self.media = Some(transfer); }
                Output { bytes: vec![bytes], replies: Vec::new() }
            }
            Err(error) => {
                // CAN inside an APC cancels a multipart Kitty upload without
                // deleting a previously committed image or ordinary screen text.
                let mut output = Output::default();
                append(&mut output, CANCEL_TRANSFER);
                failure(&mut output, &transfer.keys, error);
                output
            }
        }
    }

    pub fn discard_partial(&mut self) -> (bool, Output) {
        let discarded = self.state != 0 || self.chunked || self.utf8_remaining != 0 || self.has_pending_media();
        let mut output = Output::default();
        if let Some(transfer) = &self.media {
            failure(&mut output, &transfer.keys, "ECANCELED: media resized");
        }
        *self = Self::default(); // Dropping an incomplete media reader never unlinks it.
        (discarded, output)
    }

    pub fn finish(&mut self) -> Output {
        let mut result = Output::default();
        if self.has_pending_media() { append(&mut result, CANCEL_TRANSFER); }
        if self.state == 1 { append(&mut result, &[27]); }
        if self.state == 2 || self.state == 3 { append(&mut result, &self.apc); }
        *self = Self::default();
        result
    }

    fn process<F>(&mut self, result: &mut Output, read: &mut F)
    where F: FnMut(char, &str, u64, u64) -> Result<Media, &'static str> {
        let parsed = parse_apc(&self.apc);
        let (mut keys, payload) = match parsed { Some(value) => value, None => { append(result, &self.apc); return; } };
        let action = keys.get(&'a').map(String::as_str).unwrap_or("t").to_owned();
        let medium = keys.get(&'t').map(String::as_str).unwrap_or("d").to_owned();
        if action == "d" { self.chunked = false; }
        let was_chunked = self.chunked;
        self.chunked = keys.get(&'m').map(String::as_str) == Some("1");
        if was_chunked || medium == "d" || !matches!(action.as_str(), "t" | "T" | "q" | "f") {
            append(result, &self.apc); return;
        }
        let validated = validate_keys(&keys) && !(keys.contains_key(&'i') && keys.contains_key(&'I'));
        let name = decode_base64(&payload).and_then(|bytes| String::from_utf8(bytes).ok());
        let name = match name {
            Some(name) if validated && !self.chunked && !name.is_empty() && !name.contains('\0') => name,
            _ => { self.chunked = false; append(result, &self.apc); return; }
        };
        let offset = numeric(&keys, 'O'); let size = numeric(&keys, 'S');
        let data = if size > MAX_BYTES as u64 { Err("E2BIG: media size") } else {
            read(medium.chars().next().unwrap(), &name, offset, size)
        };
        let data = match data {
            Ok(data) if data.length <= MAX_BYTES => data,
            Ok(_) => { failure(result, &keys, "E2BIG: media size"); return; }
            Err(error) => { failure(result, &keys, error); return; }
        };
        keys.insert('t', "d".into()); keys.remove(&'S'); keys.remove(&'O'); keys.remove(&'m');
        // Consume only the command here; encoding resumes in later loop turns.
        self.media = Some(MediaTransfer::new(data, keys));
    }
}

fn plain_terminal_text(input: &[u8], pending: u8) -> Option<u8> {
    let prefix = input.len().min(pending as usize);
    if !input[..prefix].iter().all(|b| b & 0xc0 == 0x80) { return None; }
    if prefix < pending as usize { return Some(pending - prefix as u8); }
    let input = &input[prefix..];
    let remaining = match std::str::from_utf8(input) {
        Ok(_) => 0,
        Err(error) if error.error_len().is_none() => {
            let tail = &input[error.valid_up_to()..];
            let total = match tail[0] { 0xc2..=0xdf => 2, 0xe0..=0xef => 3, 0xf0..=0xf4 => 4, _ => return None };
            (total - tail.len()) as u8
        }
        Err(_) => return None,
    };
    if !input.contains(&27) { return Some(remaining); }
    let mut offset = 0;
    while let Some(next) = input[offset..].iter().position(|&b| b == 27) {
        offset += next + 1;
        if input.get(offset) != Some(&b'[') { return None; }
        offset += 1;
        while input.get(offset).is_some_and(|b| (0x30..=0x3f).contains(b)) { offset += 1; }
        while input.get(offset).is_some_and(|b| (0x20..=0x2f).contains(b)) { offset += 1; }
        if !input.get(offset).is_some_and(|b| (0x40..=0x7e).contains(b)) { return None; }
        offset += 1;
    }
    Some(remaining)
}

fn parse_apc(bytes: &[u8]) -> Option<(BTreeMap<char, String>, String)> {
    if !bytes.starts_with(b"\x1b_G") || bytes.len() < 5 { return None; }
    let end = bytes.len() - if bytes.last() == Some(&0x9c) { 1 } else { 2 };
    let command = std::str::from_utf8(&bytes[3..end]).ok()?;
    let (header, payload) = command.split_once(';').unwrap_or((command, ""));
    if header.len() > 1024 { return None; }
    let mut keys = BTreeMap::new();
    if !header.is_empty() { for item in header.split(',') {
        let (key, value) = item.split_once('=')?;
        if key.len() != 1 || value.is_empty() || keys.insert(key.chars().next()?, value.to_owned()).is_some() { return None; }
    } }
    Some((keys, payload.to_owned()))
}

fn validate_keys(keys: &BTreeMap<char, String>) -> bool {
    keys.iter().all(|(&key, value)| {
        match key {
            'a' => value.len() == 1 && "atTfpcqd".contains(value.as_str()),
            't' => value.len() == 1 && "dfts".contains(value.as_str()),
            'o' => value == "z",
            'd' => value.len() == 1 && "aAiInNcCfFpPqQrRxXyYzZ".contains(value.as_str()),
            'z' | 'H' | 'V' => value.parse::<i32>().is_ok() && !value.starts_with('+'),
            _ if "fsvSOiIpqmNxywhXYcrCUPQ".contains(key) => value.parse::<u32>().is_ok() && value.bytes().all(|b| b.is_ascii_digit()),
            _ => false,
        }
    }) && numeric(keys, 'q') <= 2 && numeric(keys, 'm') <= 1 && numeric(keys, 'C') <= 1 && numeric(keys, 'U') <= 1
}
fn numeric(keys: &BTreeMap<char, String>, key: char) -> u64 { keys.get(&key).and_then(|n| n.parse().ok()).unwrap_or(0) }
fn failure(output: &mut Output, keys: &BTreeMap<char, String>, message: &str) {
    if numeric(keys, 'q') == 2 { return; }
    let id = numeric(keys, 'i'); let number = numeric(keys, 'I'); let placement = numeric(keys, 'p');
    if id == 0 && number == 0 { return; }
    let mut header = format!("i={id}");
    if number != 0 { header.push_str(&format!(",I={number}")); }
    if placement != 0 { header.push_str(&format!(",p={placement}")); }
    output.replies.push(format!("\x1b_G{header};{message}\x1b\\").into_bytes());
}
fn append(output: &mut Output, bytes: &[u8]) { output.bytes.extend(bytes.chunks(FRAME_BYTES).map(<[u8]>::to_vec)); }

#[cfg(test)]
pub(crate) fn encode_base64(bytes: &[u8]) -> String {
    let mut out = Vec::with_capacity((bytes.len() + 2) / 3 * 4);
    encode_base64_into(bytes, &mut out);
    String::from_utf8(out).unwrap()
}
fn encode_base64_into(bytes: &[u8], out: &mut Vec<u8>) {
    for chunk in bytes.chunks(3) {
        let n = (u32::from(chunk[0]) << 16) | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8) | u32::from(*chunk.get(2).unwrap_or(&0));
        out.push(BASE64[(n >> 18) as usize]); out.push(BASE64[((n >> 12) & 63) as usize]);
        out.push(if chunk.len() > 1 { BASE64[((n >> 6) & 63) as usize] } else { b'=' });
        out.push(if chunk.len() > 2 { BASE64[(n & 63) as usize] } else { b'=' });
    }
}

// One in-progress reader per parser; encoded chunks go through the existing
// bounded output tails. No complete-image staging queue or worker is needed.
struct MediaTransfer {
    media: Media, keys: BTreeMap<char, String>, first: bool,
    carry: [u8; 2], held: usize,
}
impl MediaTransfer {
    fn new(media: Media, keys: BTreeMap<char, String>) -> Self {
        Self { media, keys, first: true, carry: [0; 2], held: 0 }
    }
    fn step(&mut self) -> Result<(bool, Vec<u8>), &'static str> {
        let mut buffer = [0u8; MEDIA_TURN_BYTES];
        buffer[..self.held].copy_from_slice(&self.carry[..self.held]);
        let wanted = self.media.length.min(MEDIA_TURN_BYTES - self.held);
        let read = if wanted == 0 { 0 } else {
            // One syscall per turn, including short/interrupted reads. read_exact
            // could loop over a slow mount indefinitely before serving control.
            match self.media.reader.read(&mut buffer[self.held..self.held + wanted]) {
                Ok(0) => return Err("EIO: media read"),
                Ok(count) => count,
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => return Ok((false, Vec::new())),
                Err(_) => return Err("EIO: media read"),
            }
        };
        self.media.length -= read;
        let available = self.held + read;
        let raw = if self.media.length == 0 { available } else { available / 3 * 3 };
        self.held = available - raw;
        self.carry[..self.held].copy_from_slice(&buffer[raw..available]);
        if raw == 0 && self.media.length != 0 { return Ok((false, Vec::new())); }
        // Only the final APC may contain base64 padding. At most two raw bytes
        // survive a short read; no extra image buffer or I/O worker is introduced.
        let header = if self.first {
            self.keys.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join(",")
        } else { String::new() };
        let frame = self.keys.get(&'a').is_some_and(|action| action == "f");
        let mut output = Vec::with_capacity((raw + 2) / 3 * 4 + header.len() + MEDIA_BLOCKS_PER_TURN * 13);
        if self.media.length == 0 { self.media.finish()?; }
        for offset in (0..raw.max(1)).step_by(MEDIA_BLOCK_BYTES) {
            let chunk = &buffer[offset..raw.min(offset + MEDIA_BLOCK_BYTES)];
            let more = self.media.length > 0 || offset + chunk.len() < raw;
            let metadata = if self.first { header.as_str() } else if frame { "a=f" } else { "" };
            output.extend_from_slice(b"\x1b_G");
            if !metadata.is_empty() { output.extend_from_slice(metadata.as_bytes()); output.push(b','); }
            output.extend_from_slice(if more { b"m=1;" } else { b"m=0;" });
            encode_base64_into(chunk, &mut output);
            output.extend_from_slice(b"\x1b\\");
            self.first = false;
        }
        Ok((self.media.length == 0, output))
    }
}

struct Media {
    reader: Box<dyn Read + Send>,
    length: usize,
    cleanup: Option<MediaCleanup>,
}
struct MediaCleanup { directory: File, name: CString, device: u64, inode: u64 }
impl Media {
    #[cfg(test)]
    fn memory(bytes: Vec<u8>) -> Self {
        Self { length: bytes.len(), reader: Box::new(std::io::Cursor::new(bytes)), cleanup: None }
    }
    fn finish(&mut self) -> Result<(), &'static str> {
        if let Some(cleanup) = self.cleanup.take() {
            let mut current = std::mem::MaybeUninit::<nix::libc::stat>::uninit();
            let status = unsafe { nix::libc::fstatat(cleanup.directory.as_raw_fd(), cleanup.name.as_ptr(), current.as_mut_ptr(), nix::libc::AT_SYMLINK_NOFOLLOW) };
            if status == 0 {
                let current = unsafe { current.assume_init() };
                if current.st_dev == cleanup.device && current.st_ino == cleanup.inode && unsafe { nix::libc::unlinkat(cleanup.directory.as_raw_fd(), cleanup.name.as_ptr(), 0) } != 0 {
                    return Err("EIO: media unlink");
                }
            }
        }
        Ok(())
    }
}

fn decode_base64(value: &str) -> Option<Vec<u8>> {
    if value.len() > 4096 || value.len() % 4 != 0 { return None; }
    let mut out = Vec::new();
    let chunks = value.as_bytes().chunks(4);
    let count = chunks.len();
    for (index, chunk) in chunks.enumerate() {
        let a = BASE64.iter().position(|&b| b == chunk[0])? as u32;
        let b = BASE64.iter().position(|&b| b == chunk[1])? as u32;
        let c = if chunk[2] == b'=' { 0 } else { BASE64.iter().position(|&b| b == chunk[2])? as u32 };
        let d = if chunk[3] == b'=' { 0 } else { BASE64.iter().position(|&b| b == chunk[3])? as u32 };
        if (chunk[2] == b'=' && chunk[3] != b'=') || (index + 1 < count && (chunk[2] == b'=' || chunk[3] == b'=')) { return None; }
        let n = (a << 18) | (b << 12) | (c << 6) | d;
        out.push((n >> 16) as u8);
        if chunk[2] != b'=' { out.push((n >> 8) as u8); }
        if chunk[3] != b'=' { out.push(n as u8); }
    }
    Some(out)
}

fn sensitive(path: &Path) -> bool {
    path.starts_with("/proc") || path.starts_with("/sys") || path.starts_with("/dev") && !path.starts_with("/dev/shm")
}

fn read_media(medium: char, name: &str, offset: u64, size: u64) -> Result<Media, &'static str> {
    let requested = match medium {
        's' if name.starts_with('/') && name.len() > 1 && name.len() <= 255 && !name[1..].contains('/') => format!("/dev/shm{name}"),
        's' => return Err("EINVAL: shared memory name"),
        'f' | 't' => name.to_owned(),
        _ => return Err("EINVAL: media type"),
    };
    let requested = Path::new(&requested);
    if !requested.is_absolute() || sensitive(requested) { return Err("EACCES: media path"); }
    let canonical = std::fs::canonicalize(requested).map_err(|_| "ENOENT: media file")?;
    if sensitive(&canonical) { return Err("EACCES: sensitive path"); }
    if medium == 's' && !canonical.starts_with("/dev/shm") { return Err("EACCES: shared memory path"); }
    let mut directory = File::open("/").map_err(|_| "EIO: media directory")?;
    let components: Vec<_> = canonical.components().filter_map(|c| match c { Component::Normal(s) => Some(s), _ => None }).collect();
    let (name, parents) = components.split_last().ok_or("EACCES: regular files only")?;
    for parent in parents {
        let part = CString::new(parent.as_bytes()).map_err(|_| "EINVAL: media path")?;
        let fd = unsafe { nix::libc::openat(directory.as_raw_fd(), part.as_ptr(), nix::libc::O_PATH | nix::libc::O_DIRECTORY | nix::libc::O_NOFOLLOW | nix::libc::O_CLOEXEC) };
        if fd < 0 { return Err("EACCES: media path"); }
        directory = unsafe { File::from_raw_fd(fd) };
    }
    let name = CString::new(name.as_bytes()).map_err(|_| "EINVAL: media path")?;
    let fd = unsafe { nix::libc::openat(directory.as_raw_fd(), name.as_ptr(), nix::libc::O_RDONLY | nix::libc::O_NONBLOCK | nix::libc::O_NOFOLLOW | nix::libc::O_CLOEXEC) };
    if fd < 0 { return Err("ENOENT: media file"); }
    let mut file = unsafe { File::from_raw_fd(fd) };
    let metadata = file.metadata().map_err(|_| "EIO: media metadata")?;
    if !metadata.is_file() { return Err("EACCES: regular files only"); }
    if offset > metadata.len() { return Err("EINVAL: media offset"); }
    let length = if size == 0 { metadata.len() - offset } else { size };
    if length > MAX_BYTES as u64 { return Err("E2BIG: media size"); }
    if length > metadata.len() - offset { return Err("EINVAL: media size"); }
    file.seek(SeekFrom::Start(offset)).map_err(|_| "EIO: media offset")?;
    let temporary = canonical.starts_with("/tmp") || canonical.starts_with("/dev/shm");
    let remove = medium == 's' || medium == 't' && temporary && canonical.as_os_str().as_bytes().windows(21).any(|w| w == b"tty-graphics-protocol");
    Ok(Media {
        reader: Box::new(file), length: length as usize,
        cleanup: remove.then_some(MediaCleanup { directory, name, device: metadata.dev(), inode: metadata.ino() }),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);

    fn read_media_bytes(medium: char, name: &str, offset: u64, size: u64) -> Result<Vec<u8>, &'static str> {
        let mut media = read_media(medium, name, offset, size)?;
        let mut bytes = vec![0; media.length];
        media.reader.read_exact(&mut bytes).map_err(|_| "EIO: media read")?;
        media.finish()?;
        Ok(bytes)
    }

    fn encode_media(media: Media, header: &str, _action: &str) -> Result<Vec<Vec<u8>>, &'static str> {
        let keys = header.split(',').map(|item| {
            let (key, value) = item.split_once('=').unwrap(); (key.chars().next().unwrap(), value.to_owned())
        }).collect();
        let mut transfer = MediaTransfer::new(media, keys);
        let mut bytes = Vec::new();
        loop {
            let (done, batch) = transfer.step()?;
            // Split complete APCs so the existing per-chunk assertions still
            // verify the 4096-byte payload and continuation metadata boundaries.
            let mut start = 0;
            for index in 0..batch.len().saturating_sub(1) {
                if &batch[index..index+2] == b"\x1b\\" { bytes.push(batch[start..index+2].to_vec()); start = index+2; }
            }
            if done { return Ok(bytes); }
        }
    }

    fn sequence(header: &str, payload: &str) -> Vec<u8> { format!("\x1b_G{header};{payload}\x1b\\").into_bytes() }
    fn flatten(output: Output) -> Vec<u8> { output.bytes.concat() }
    fn fixture() -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("tty-graphics-protocol-test-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir(&p).unwrap(); p
    }

    #[test]
    fn short_and_interrupted_reads_yield_without_padding_or_losing_bytes() {
        use std::sync::Arc;
        struct Short { bytes: std::io::Cursor<Vec<u8>>, width: usize, calls: Arc<AtomicUsize> }
        impl Read for Short {
            fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
                let count = self.calls.fetch_add(1, Ordering::Relaxed);
                if count % 4 == 0 { return Err(std::io::ErrorKind::Interrupted.into()); }
                let length = buffer.len().min(self.width);
                self.bytes.read(&mut buffer[..length])
            }
        }
        for width in [1, 2, 3, 7, 4095] {
            let bytes: Vec<_> = (0..8201).map(|n| (n % 251) as u8).collect();
            let calls = Arc::new(AtomicUsize::new(0));
            let mut transfer = MediaTransfer::new(Media { reader: Box::new(Short {
                bytes: std::io::Cursor::new(bytes.clone()), width, calls: calls.clone() }),
                length: bytes.len(), cleanup: None }, [('i', "7".into())].into());
            let mut encoded = String::new();
            loop {
                let previous = calls.load(Ordering::Relaxed);
                let (done, output) = transfer.step().unwrap();
                assert_eq!(previous + 1, calls.load(Ordering::Relaxed));
                let mut start = 0;
                for end in 0..output.len().saturating_sub(1) {
                    if &output[end..end + 2] == b"\x1b\\" {
                        let (keys, payload) = parse_apc(&output[start..end + 2]).unwrap();
                        if numeric(&keys, 'm') == 1 { assert!(!payload.contains('=')); }
                        encoded.push_str(&payload); start = end + 2;
                    }
                }
                if done { break; }
            }
            assert_eq!(encode_base64(&bytes), encoded);
        }
    }

    #[test]
    fn media_work_yields_between_bounded_reads_and_cancels_without_committing() {
        use std::sync::Arc;
        struct CountingReader(Arc<AtomicUsize>);
        impl Read for CountingReader {
            fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                self.0.fetch_add(1, Ordering::Relaxed); bytes.fill(7); Ok(bytes.len())
            }
        }
        let calls = Arc::new(AtomicUsize::new(0));
        let mut transport = GraphicsTransport::default();
        let command = sequence("i=1,t=f", &encode_base64(b"/image"));
        let (used, first) = transport.accept_prefix_inner(&command, 65536, |_, _, _, _| Ok(Media {
            reader: Box::new(CountingReader(calls.clone())), length: 196608, cleanup: None,
        }), true);
        assert_eq!(used, command.len()); assert!(first.bytes.is_empty());
        assert_eq!(calls.load(Ordering::Relaxed), 0, "parsing a path does not run the conversion");
        for turn in 1..=2 {
            let bytes = flatten(transport.advance_media());
            assert!(bytes.len() <= FRAME_BYTES);
            assert!(!bytes.windows(4).any(|b| b == b"m=0;"));
            assert_eq!(calls.load(Ordering::Relaxed), turn);
            assert!(transport.has_pending_media());
        }
        let (discarded, output) = transport.discard_partial();
        assert!(discarded);
        assert_eq!(output.replies, vec![sequence("i=1", "ECANCELED: media resized")]);
        assert!(!transport.has_pending_media());
        assert!(transport.advance_media().bytes.is_empty());
    }

    #[test]
    fn resize_cancellation_honors_quiet_and_identity_and_replies_once() {
        for (header, expected) in [
            ("i=7,t=f", Some("i=7")),
            ("I=9,p=2,q=1,t=f", Some("i=0,I=9,p=2")),
            ("i=7,q=2,t=f", None),
            ("t=f", None),
        ] {
            let mut transport = GraphicsTransport::default();
            transport.accept_prefix_inner(&sequence(header, &encode_base64(b"/image")), 65536,
                |_, _, _, _| Ok(Media::memory(vec![7; 65536])), true);
            let (discarded, output) = transport.discard_partial();
            assert!(discarded); assert!(output.bytes.is_empty());
            assert_eq!(output.replies, expected.map(|keys| sequence(keys, "ECANCELED: media resized")).into_iter().collect::<Vec<_>>());
            let (discarded, output) = transport.discard_partial();
            assert!(!discarded); assert!(output.replies.is_empty());
            assert_eq!(flatten(transport.accept_prefix(b"after").1), b"after");
        }
    }

    #[test]
    fn maximum_image_uses_one_read_per_turn_with_unchanged_payload() {
        use std::sync::Arc;
        struct CountingReader(Arc<AtomicUsize>);
        impl Read for CountingReader {
            fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                assert!(bytes.len() <= MEDIA_TURN_BYTES);
                self.0.fetch_add(1, Ordering::Relaxed); bytes.fill(7); Ok(bytes.len())
            }
        }
        let calls = Arc::new(AtomicUsize::new(0));
        let media = Media { reader: Box::new(CountingReader(calls.clone())), length: MAX_BYTES, cleanup: None };
        let chunks = encode_media(media, "i=1,t=d", "t").unwrap();
        let encoded: String = chunks.iter().map(|chunk| parse_apc(chunk).unwrap().1).collect();
        assert_eq!(encoded, encode_base64(&vec![7; MAX_BYTES]));
        assert_eq!(calls.load(Ordering::Relaxed), 293);
    }

    #[test]
    fn failure_after_published_chunks_cancels_upload_and_keeps_source() {
        let dir = fixture(); let file = dir.join("partial-image");
        std::fs::write(&file, vec![7; 128 * 1024]).unwrap();
        let command = sequence("i=9,t=t", &encode_base64(file.to_str().unwrap().as_bytes()));
        let mut transport = GraphicsTransport::default();
        transport.accept_prefix(&command);
        let first = flatten(transport.advance_media());
        assert!(first.starts_with(b"\x1b_G"));
        assert!(!first.windows(4).any(|b| b == b"m=0;"));
        std::fs::OpenOptions::new().write(true).open(&file).unwrap().set_len(0).unwrap();
        let failed = transport.advance_media();
        assert_eq!(failed.replies, vec![sequence("i=9", "EIO: media read")]);
        assert_eq!(flatten(failed), CANCEL_TRANSFER);
        assert!(!transport.has_pending_media()); assert!(file.exists());
        let (_, normal) = transport.accept_prefix(b"after");
        assert_eq!(flatten(normal), b"after");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn chunked_encoding_matches_base64_at_boundaries_and_discards_failed_reads() {
        for size in [0, 1, 2, 3, 3071, 3072, 3073, 6145, MAX_BYTES] {
            let original: Vec<u8> = (0..size).map(|n| (n % 251) as u8).collect();
            let chunks = encode_media(Media::memory(original.clone()), "i=1,t=d", "T").unwrap();
            let mut encoded = String::new();
            for (index, chunk) in chunks.iter().enumerate() {
                let (keys, payload) = parse_apc(chunk).unwrap();
                assert_eq!(numeric(&keys, 'm'), u64::from(index + 1 < chunks.len()));
                assert!(payload.len() <= 4096);
                encoded.push_str(&payload);
            }
            assert_eq!(encoded, encode_base64(&original));
        }
        let dir = fixture(); let file = dir.join("short-read");
        std::fs::write(&file, vec![1; 6144]).unwrap();
        let mut media = Some(read_media('t', file.to_str().unwrap(), 0, 0).unwrap());
        std::fs::OpenOptions::new().write(true).open(&file).unwrap().set_len(4096).unwrap();
        let input = [b"before".to_vec(), sequence("i=8,t=t", &encode_base64(file.to_str().unwrap().as_bytes())), b"after".to_vec()].concat();
        let mut transport = GraphicsTransport::default();
        let (used, first) = transport.accept_prefix_inner(&input, usize::MAX, |_, _, _, _| Ok(media.take().unwrap()), true);
        assert_eq!(flatten(first), b"before");
        let partial = transport.advance_media();
        assert!(partial.replies.is_empty());
        assert!(flatten(partial).windows(4).all(|part| part != b"m=0;"));
        let failed = transport.advance_media();
        assert_eq!(failed.replies, vec![sequence("i=8", "EIO: media read")]);
        assert_eq!(flatten(failed), CANCEL_TRANSFER);
        let (_, following) = transport.accept_prefix(&input[used..]);
        assert_eq!(flatten(following), b"after");
        assert!(file.exists(), "a partial read must not unlink the source");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn large_media_prefixes_apply_backpressure_without_losing_following_commands() {
        let command = format!("\x1b_Gt=f,i=1;{}\x1b\\", encode_base64(b"/ignored"));
        let input = command.repeat(2);
        let mut transport = GraphicsTransport::default();
        let (consumed, first) = transport.accept_prefix_with(input.as_bytes(), 64 * 1024,
            |_, _, _, _| Ok(vec![7; 128 * 1024]));
        assert_eq!(consumed, command.len());
        let (remaining, second) = transport.accept_prefix_with(&input.as_bytes()[consumed..], 64 * 1024,
            |_, _, _, _| Ok(vec![7; 128 * 1024]));
        assert_eq!(remaining, command.len());
        let expected = GraphicsTransport::default().accept_with(input.as_bytes(), |_, _, _, _| Ok(vec![7; 128 * 1024]));
        let mut actual = flatten(first); actual.extend(flatten(second));
        assert_eq!(actual, flatten(expected));
    }

    #[test]
    fn fragmented_file_transfer_is_direct_and_preserves_text() {
        let input = [b"before".to_vec(), sequence("a=T,i=3,t=f,f=24,s=1,v=1", &encode_base64(b"/tmp/red")), b"after".to_vec()].concat();
        for split in 0..=input.len() {
            let mut t = GraphicsTransport::default();
            let mut calls = 0;
            let mut resolver = |medium, name: &str, offset, size| {
                calls += 1; assert_eq!(medium, 'f'); assert_eq!(name, "/tmp/red"); assert_eq!((offset, size), (0, 0));
                Ok(vec![255, 0, 0])
            };
            let mut output = flatten(t.accept_with(&input[..split], &mut resolver));
            output.extend(flatten(t.accept_with(&input[split..], &mut resolver)));
            assert_eq!(calls, 1);
            let text = String::from_utf8(output).unwrap();
            assert!(text.starts_with("before\x1b_G")); assert!(text.ends_with(";/wAA\x1b\\after"));
            assert!(text.contains("t=d")); assert!(!text.contains("t=f"));
        }
    }

    #[test]
    fn direct_utf8_and_nested_strings_do_not_read_files() {
        let command = sequence("a=T,i=1,t=f", &encode_base64(b"/etc/passwd"));
        let samples = [
            sequence("a=T,i=1,f=24,s=1,v=1", "/wAA"),
            [b"\x1b]0;".to_vec(), command.clone(), vec![7]].concat(),
            [b"\x1bPq".to_vec(), command, b"\x1b\\".to_vec()].concat(),
            "한글\u{10eeee}\u{9f}".as_bytes().to_vec(),
        ];
        for input in samples {
            let mut t = GraphicsTransport::default();
            let output = t.accept_with(&input, |_, _, _, _| panic!("unexpected read"));
            assert_eq!(flatten(output), input);
        }
    }

    #[test]
    fn plain_text_fast_path_preserves_every_fragment_boundary() {
        let plain = b"ordinary output\r\n".repeat(4096);
        let mut t = GraphicsTransport::default();
        let (consumed, output) = t.accept_prefix(&plain);
        assert_eq!(consumed, plain.len());
        assert_eq!(flatten(output), plain);
        let command = sequence("a=T,i=1,t=f", &encode_base64(b"/guest/image"));
        let input = [b"prefix".to_vec(), "한글".as_bytes().to_vec(), command, b"suffix".to_vec()].concat();
        for width in 1..=input.len() {
            let mut t = GraphicsTransport::default();
            let mut output = Vec::new();
            let mut reads = 0;
            for chunk in input.chunks(width) {
                output.extend(flatten(t.accept_with(chunk, |_, _, _, _| { reads += 1; Ok(vec![1, 2, 3]) })));
            }
            output.extend(flatten(t.finish()));
            assert_eq!(reads, 1, "chunk width {width}");
            assert!(output.starts_with("prefix한글".as_bytes()));
            assert!(output.ends_with(b"suffix"));
            assert!(String::from_utf8(output).unwrap().contains("AQID"));
        }
    }

    #[test]
    fn utf8_and_ansi_fast_path_matches_parser_across_every_fragment_width() {
        let command = sequence("a=T,i=1,t=f", &encode_base64(b"/guest/image"));
        let samples = vec![
            "한글 😀 é\x1b[38;2;1;2;3m색상\x1b[0m\r\n".as_bytes().repeat(3),
            ["한글\x1b[2J".as_bytes(), &command, b"\x1b[?25htext"].concat(),
            [b"\x1b]0;title ".as_slice(), &command, b"\x07\x1bPignored", &command, b"\x1b\\tail"].concat(),
            [b"\xf0\x9f".as_slice(), b"\x9f\x9c", b"\x9fGa=q,t=f;L2ltYWdl\x9c", b"\xe0\x80\x80\xff\x1b[\x18"].concat(),
        ];
        for sample in samples {
            for width in 1..=sample.len() {
                let mut fast = GraphicsTransport::default();
                let mut reference = GraphicsTransport::default();
                for chunk in sample.chunks(width) {
                    let mut offset = 0;
                    while offset < chunk.len() || fast.has_pending_media() {
                        let (used, actual) = fast.accept_prefix_inner(&chunk[offset..], usize::MAX, |_, _, _, _| Ok(Media::memory(vec![1, 2, 3])), true);
                        let (expected_used, expected) = reference.accept_prefix_inner(&chunk[offset..], usize::MAX, |_, _, _, _| Ok(Media::memory(vec![1, 2, 3])), false);
                        assert_eq!(used, expected_used, "width {width}");
                        offset += used;
                        assert_eq!(fast.has_pending_media(), reference.has_pending_media());
                        assert_eq!(actual.replies, expected.replies);
                        assert_eq!(flatten(actual), flatten(expected), "width {width}");
                        assert_eq!(fast.state, reference.state);
                        assert_eq!(fast.utf8_remaining, reference.utf8_remaining);
                        assert_eq!(fast.apc, reference.apc);
                        assert_eq!(fast.chunked, reference.chunked);
                    }
                }
                assert_eq!(flatten(fast.finish()), flatten(reference.finish()));
            }
        }
        assert_eq!(plain_terminal_text("한글\x1b[31m빨강\x1b[0m".as_bytes(), 0), Some(0));
        assert_eq!(plain_terminal_text(&[0xed, 0x95], 0), Some(1));
        assert_eq!(plain_terminal_text(&[0x9c, 0xea], 1), Some(2));
        assert_eq!(plain_terminal_text(b"\x1b_Gt=f;path\x1b\\", 0), None);
        assert_eq!(plain_terminal_text(b"\x1b[31", 0), None);
        assert_eq!(plain_terminal_text(&[0x9f], 0), None);
    }

    #[test]
    fn media_errors_honor_quiet_and_keep_paths_private() {
        for quiet in 0..=2 {
            let mut t = GraphicsTransport::default();
            let output = t.accept_with(&sequence(&format!("a=q,i=42,t=s,q={quiet}"), &encode_base64(b"/missing")), |_, _, _, _| Err("ENOENT: media file"));
            assert!(output.bytes.is_empty());
            if quiet == 2 { assert!(output.replies.is_empty()); }
            else { assert_eq!(output.replies, vec![sequence("i=42", "ENOENT: media file")]); }
        }
    }

    #[test]
    fn expanded_animation_uses_frame_action_in_continuations() {
        let mut t = GraphicsTransport::default();
        let output = t.accept_with(&sequence("a=f,i=1,t=f,O=10,S=8000", &encode_base64(b"/tmp/frame")), |_, _, offset, size| {
            assert_eq!((offset, size), (10, 8000)); Ok(vec![7; 8000])
        });
        let text = String::from_utf8(flatten(output)).unwrap();
        assert!(text.contains("\x1b_Ga=f,m=1;")); assert!(text.contains("\x1b_Ga=f,m=0;"));
        assert!(!text.contains("O=10")); assert!(!text.contains("S=8000"));
    }

    #[test]
    fn oversized_and_cancelled_apcs_never_resolve_paths() {
        let input = [b"\x1b_Ga=T,t=f;".to_vec(), vec![b'A'; 6000], b"\x1b\\safe".to_vec()].concat();
        let mut t = GraphicsTransport::default();
        assert_eq!(flatten(t.accept_with(&input, |_, _, _, _| panic!("read"))), input);
        let input = b"\x1b_Ga=T,t=f;L3RtcA==\x18safe";
        assert_eq!(flatten(t.accept_with(input, |_, _, _, _| panic!("read"))), input);
    }

    #[test]
    fn read_slice_follows_symlinks_but_refuses_special_files() {
        let dir = fixture(); let file = dir.join("pixels"); let link = dir.join("link");
        std::fs::write(&file, [9, 255, 0, 0, 8]).unwrap();
        std::os::unix::fs::symlink(&file, &link).unwrap();
        assert_eq!(read_media_bytes('f', link.to_str().unwrap(), 1, 3).unwrap(), [255, 0, 0]);
        assert!(read_media_bytes('f', file.to_str().unwrap(), 8, 0).is_err());
        assert!(read_media_bytes('f', "/proc/self/status", 0, 0).is_err());
        assert!(read_media_bytes('f', "/dev/zero", 0, 3).is_err());
        let fifo = dir.join("fifo"); let name = CString::new(fifo.as_os_str().as_bytes()).unwrap();
        assert_eq!(unsafe { nix::libc::mkfifo(name.as_ptr(), 0o600) }, 0);
        assert_eq!(read_media_bytes('f', fifo.to_str().unwrap(), 0, 0).unwrap_err(), "EACCES: regular files only");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn temporary_media_is_removed_only_after_successful_read() {
        let dir = fixture(); let file = dir.join("frame");
        std::fs::write(&file, [1, 2, 3]).unwrap();
        assert!(read_media_bytes('t', file.to_str().unwrap(), 0, 9).is_err()); assert!(file.exists());
        assert_eq!(read_media_bytes('t', file.to_str().unwrap(), 0, 0).unwrap(), [1, 2, 3]); assert!(!file.exists());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn base64_padding_and_round_trip() {
        for count in 0..100 {
            let input: Vec<_> = (0..count).map(|n| n as u8).collect();
            assert_eq!(decode_base64(&encode_base64(&input)), Some(input));
        }
        assert!(decode_base64("a===").is_none()); assert!(decode_base64("AAAA!!!!").is_none());
        assert!(decode_base64("AA==AAAA").is_none());
    }
}
