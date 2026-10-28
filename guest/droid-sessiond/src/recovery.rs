//! Native TSR1/TRS2 transport. PTYs stay in sessiond; socket replacement never
//! reissues a session RPC. Credit is released by consumption, not by receipt.
use std::collections::VecDeque;
use std::io::{self, BufReader, Read, Write};
use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

const WINDOW: usize = 256 * 1024;
const CHUNK: usize = 32 * 1024;
const LEASE: Duration = Duration::from_secs(60);
const HEARTBEAT: Duration = Duration::from_secs(5);
const IO_TIMEOUT: Duration = Duration::from_secs(20);
const MAX_OFFSET: u64 = i64::MAX as u64;
const ENDPOINT: &str = "/run/droid-sessiond/relay.sock";

fn invalid(reason: &'static str) -> io::Error { io::Error::new(io::ErrorKind::InvalidData, reason) }
fn closed() -> io::Error { io::Error::new(io::ErrorKind::BrokenPipe, "session replay owner closed") }
fn shutdown(socket: &UnixStream) { let _ = socket.shutdown(Shutdown::Both); }
fn worker(name: &str, run: impl FnOnce() + Send + 'static) -> io::Result<()> {
    std::thread::Builder::new().name(name.into()).stack_size(256 * 1024).spawn(run).map(|_| ())
}

struct Link {
    id: u64,
    socket: Arc<UnixStream>,
    cursor: u64,
    consumed: u64,
    window: usize,
}

#[derive(Default)]
struct State {
    incoming: VecDeque<u8>,
    received: u64,
    consumed: u64,
    outgoing: VecDeque<u8>,
    produced: u64,
    acknowledged: u64,
    link: Option<Link>,
    next_link: u64,
    detached_at: Option<Instant>,
    closed: bool,
}

struct Replay {
    state: Mutex<State>,
    changed: Condvar,
    application: UnixStream,
}

impl Replay {
    fn new(application: UnixStream) -> io::Result<Arc<Self>> {
        let owner = Arc::new(Self { state: Mutex::new(State { detached_at: Some(Instant::now()), ..State::default() }),
            changed: Condvar::new(), application });
        for from_application in [true, false] {
            let stream = owner.clone();
            if let Err(error) = worker("session-app", move || {
                let result = if from_application { stream.from_application() } else { stream.to_application() };
                if let Err(error) = result {
                    eprintln!("session-recovery: application failed ({:?})", error.kind());
                }
                stream.close();
            }) { owner.close(); return Err(error); }
        }
        Ok(owner)
    }

    fn expired(&self) -> bool {
        let state = self.state.lock().unwrap();
        state.closed || (state.link.is_none() && state.detached_at.is_some_and(|at| at.elapsed() >= LEASE))
    }

    fn close(&self) {
        let link = {
            let mut state = self.state.lock().unwrap();
            if state.closed { return; }
            state.closed = true;
            self.changed.notify_all();
            state.link.take()
        };
        shutdown(&self.application);
        if let Some(link) = link { shutdown(&link.socket); }
    }

    fn detach(&self, id: u64, reason: &'static str) {
        let link = {
            let mut state = self.state.lock().unwrap();
            if state.link.as_ref().map(|link| link.id) != Some(id) { return; }
            state.detached_at = Some(Instant::now());
            self.changed.notify_all();
            state.link.take()
        };
        if let Some(link) = link { shutdown(&link.socket); }
        eprintln!("session-recovery: raw link detached ({reason})");
    }

    fn from_application(&self) -> io::Result<()> {
        let mut bytes = [0; CHUNK];
        loop {
            let count = (&self.application).read(&mut bytes)?;
            if count == 0 { return Ok(()); }
            let mut offset = 0;
            let mut state = self.state.lock().unwrap();
            while offset < count {
                while state.outgoing.len() == WINDOW && !state.closed { state = self.changed.wait(state).unwrap(); }
                if state.closed { return Err(closed()); }
                let length = (count - offset).min(WINDOW - state.outgoing.len());
                if state.produced > MAX_OFFSET - length as u64 { return Err(invalid("offset exhausted")); }
                state.outgoing.extend(&bytes[offset..offset + length]);
                state.produced += length as u64;
                offset += length;
                self.changed.notify_all();
            }
        }
    }

    fn to_application(&self) -> io::Result<()> {
        let mut bytes = [0; CHUNK];
        loop {
            let count = {
                let mut state = self.state.lock().unwrap();
                while state.incoming.is_empty() && !state.closed { state = self.changed.wait(state).unwrap(); }
                if state.closed { return Ok(()); }
                let count = CHUNK.min(state.incoming.len());
                for byte in &mut bytes[..count] { *byte = state.incoming.pop_front().unwrap(); }
                state.consumed += count as u64;
                self.changed.notify_all();
                count
            };
            (&self.application).write_all(&bytes[..count])?;
        }
    }

    fn attach(self: &Arc<Self>, mut socket: UnixStream) -> io::Result<()> {
        // The listener serializes handshakes. Retire a previous raw link before
        // advertising offsets so only one receiver can advance this generation.
        let previous = self.state.lock().unwrap().link.as_ref().map(|link| link.id);
        if let Some(id) = previous { self.detach(id, "replaced"); }
        socket.set_read_timeout(Some(IO_TIMEOUT))?;
        socket.set_write_timeout(Some(IO_TIMEOUT))?;
        let mut handshake = Vec::with_capacity(24);
        {
            let mut state = self.state.lock().unwrap();
            if state.closed { return Err(closed()); }
            // Failed initial handshakes must also expire their application socket.
            state.detached_at.get_or_insert_with(Instant::now);
            handshake.extend(b"TRS2"); handshake.extend((WINDOW as u32).to_be_bytes());
            handshake.extend(state.received.to_be_bytes()); handshake.extend(state.consumed.to_be_bytes());
        }
        socket.write_all(&handshake)?;
        let mut peer = [0; 24];
        socket.read_exact(&mut peer)?;
        let window = u32::from_be_bytes(peer[4..8].try_into().unwrap()) as usize;
        let received = u64::from_be_bytes(peer[8..16].try_into().unwrap());
        let consumed = u64::from_be_bytes(peer[16..24].try_into().unwrap());
        let socket = Arc::new(socket);
        let id = {
            let mut state = self.state.lock().unwrap();
            if state.closed { return Err(closed()); }
            if &peer[..4] != b"TRS2" || !(1..=WINDOW).contains(&window) ||
                consumed < state.acknowledged || consumed > received || received > state.produced || received - consumed > window as u64 {
                return Err(invalid("invalid replay generation"));
            }
            let count = (consumed - state.acknowledged) as usize;
            state.outgoing.drain(..count);
            state.acknowledged = consumed;
            state.next_link += 1;
            let id = state.next_link;
            state.link = Some(Link { id, socket: socket.clone(), cursor: received, consumed, window });
            state.detached_at = None;
            self.changed.notify_all();
            id
        };
        for receiving in [true, false] {
            let owner = self.clone();
            let raw = socket.clone();
            if let Err(error) = worker("session-replay", move || {
                let result = if receiving { owner.receive(id, &raw) } else { owner.send(id, &raw) };
                owner.detach(id, if result.is_ok() { "closed" } else if receiving { "read" } else { "write" });
            }) { self.detach(id, "worker"); return Err(error); }
        }
        Ok(())
    }

    fn receive(&self, id: u64, socket: &UnixStream) -> io::Result<()> {
        let mut input = BufReader::with_capacity(CHUNK + 13, socket);
        let mut header = [0; 13];
        let mut bytes = [0; CHUNK];
        loop {
            input.read_exact(&mut header)?;
            let kind = header[0];
            let position = u64::from_be_bytes(header[1..9].try_into().unwrap());
            let count = u32::from_be_bytes(header[9..13].try_into().unwrap()) as usize;
            if count > CHUNK || position > MAX_OFFSET - count as u64 { return Err(invalid("frame bounds")); }
            input.read_exact(&mut bytes[..count])?;
            let mut state = self.state.lock().unwrap();
            let Some(link) = state.link.as_ref().filter(|link| link.id == id) else { return Ok(()); };
            match kind {
                1 => {
                    if count == 0 || position > state.received { return Err(invalid("data gap")); }
                    let skip = (state.received - position).min(count as u64) as usize;
                    if count - skip > WINDOW - state.incoming.len() { return Err(invalid("receive credit")); }
                    state.incoming.extend(&bytes[skip..count]);
                    state.received += (count - skip) as u64;
                }
                2 => {
                    if count != 0 || position < state.acknowledged || position > link.cursor { return Err(invalid("acknowledgement")); }
                    let count = (position - state.acknowledged) as usize;
                    state.outgoing.drain(..count);
                    state.acknowledged = position;
                    state.link.as_mut().unwrap().consumed = position;
                }
                3 if count == 0 && position == 0 => (),
                _ => return Err(invalid("frame kind")),
            }
            self.changed.notify_all();
        }
    }

    fn send(&self, id: u64, mut socket: &UnixStream) -> io::Result<()> {
        let mut acknowledged = None;
        let mut last_write = Instant::now();
        let mut batch = Vec::with_capacity(CHUNK * 4 + 13 * 5);
        loop {
            batch.clear();
            {
                let mut state = self.state.lock().unwrap();
                loop {
                    let Some(link) = state.link.as_ref().filter(|link| link.id == id) else { return Ok(()); };
                    if state.closed { return Ok(()); }
                    let (mut cursor, consumed, window) = (link.cursor, link.consumed, link.window);
                    if acknowledged != Some(state.consumed) {
                        acknowledged = Some(state.consumed);
                        frame_header(&mut batch, 2, state.consumed, 0);
                    }
                    for _ in 0..4 {
                        let available = (state.produced - cursor).min(window as u64 - (cursor - consumed));
                        if available == 0 { break; }
                        let count = CHUNK.min(available as usize);
                        frame_header(&mut batch, 1, cursor, count);
                        let start = (cursor - state.acknowledged) as usize;
                        batch.extend(state.outgoing.range(start..start + count));
                        cursor += count as u64;
                    }
                    state.link.as_mut().unwrap().cursor = cursor;
                    if !batch.is_empty() { break; }
                    if last_write.elapsed() >= HEARTBEAT { frame_header(&mut batch, 3, 0, 0); break; }
                    let remaining = HEARTBEAT.saturating_sub(last_write.elapsed());
                    state = self.changed.wait_timeout(state, remaining).unwrap().0;
                }
            }
            socket.write_all(&batch)?;
            last_write = Instant::now();
        }
    }
}

fn frame_header(bytes: &mut Vec<u8>, kind: u8, position: u64, length: usize) {
    bytes.push(kind); bytes.extend(position.to_be_bytes()); bytes.extend((length as u32).to_be_bytes());
}

struct Relay {
    endpoint: String,
    owner: Option<([u8; 16], Arc<Replay>)>,
}

impl Relay {
    fn expire(&mut self) {
        if self.owner.as_ref().is_some_and(|(_, owner)| owner.expired()) {
            self.owner.take().unwrap().1.close();
        }
    }

    fn attach(&mut self, mut socket: UnixStream) -> io::Result<()> {
        socket.set_read_timeout(Some(Duration::from_secs(10)))?;
        socket.set_write_timeout(Some(Duration::from_secs(10)))?;
        let mut request = [0; 21];
        socket.read_exact(&mut request)?;
        if &request[..4] != b"TSR1" || request[20] > 2 { return Err(invalid("owner handshake")); }
        let token: [u8; 16] = request[4..20].try_into().unwrap();
        self.expire();
        if request[20] == 2 {
            if self.owner.as_ref().is_some_and(|(current, _)| *current != token) {
                return socket.write_all(&[1]);
            }
            if let Some((_, owner)) = self.owner.take() { owner.close(); }
            return socket.write_all(&[0]);
        }
        if let Some((current, _)) = &self.owner {
            if *current != token { return socket.write_all(&[1]); }
        } else {
            if request[20] != 1 { return socket.write_all(&[2]); }
            self.owner = Some((token, Replay::new(UnixStream::connect(&self.endpoint)?)?));
        }
        socket.write_all(&[0])?;
        self.owner.as_ref().unwrap().1.attach(socket)
    }
}

impl Drop for Relay {
    fn drop(&mut self) { if let Some((_, owner)) = self.owner.take() { owner.close(); } }
}

pub fn serve() -> Result<(), crate::vsock::VsockError> {
    let listener = crate::vsock::VsockListener::bind(40507)?;
    let mut relay = Relay { endpoint: ENDPOINT.into(), owner: None };
    loop {
        relay.expire();
        if !listener.wait(1000)? { continue; }
        match listener.accept() {
            Ok(connection) => {
                if let Err(error) = relay.attach(connection.into_socket()) {
                    eprintln!("session-recovery: handshake failed ({:?})", error.kind());
                }
            }
            Err(crate::vsock::VsockError::UntrustedPeer) => (),
            Err(error) => return Err(error),
        }
    }
}

#[cfg(test)]
#[path = "recovery_tests.rs"]
mod tests;
