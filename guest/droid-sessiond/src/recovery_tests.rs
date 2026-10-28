use super::*;

#[path = "recovery_benchmark.rs"]
mod benchmark;

struct Peer { socket: UnixStream, consumed: u64 }
impl Peer {
    fn handshake(mut socket: UnixStream, received: u64, consumed: u64, window: usize) -> Self {
        socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        socket.set_write_timeout(Some(Duration::from_secs(2))).unwrap();
        let mut header = [0; 24]; socket.read_exact(&mut header).unwrap();
        assert_eq!(&header[..4], b"TRS2");
        assert_eq!(WINDOW as u32, u32::from_be_bytes(header[4..8].try_into().unwrap()));
        header[4..8].copy_from_slice(&(window as u32).to_be_bytes());
        header[8..16].copy_from_slice(&received.to_be_bytes());
        header[16..24].copy_from_slice(&consumed.to_be_bytes());
        socket.write_all(&header).unwrap();
        Self { socket, consumed }
    }
    fn connect(owner: &Arc<Replay>, received: u64, consumed: u64, window: usize) -> Self {
        let (host, guest) = UnixStream::pair().unwrap();
        let owner = owner.clone();
        let task = std::thread::spawn(move || owner.attach(guest));
        let peer = Self::handshake(host, received, consumed, window);
        task.join().unwrap().unwrap();
        peer
    }
    fn send(&mut self, kind: u8, position: u64, data: &[u8]) {
        let mut frame = Vec::new(); frame_header(&mut frame, kind, position, data.len()); frame.extend(data);
        self.socket.write_all(&frame).unwrap();
    }
    fn frame(&mut self) -> (u8, u64, Vec<u8>) {
        let mut header = [0; 13]; self.socket.read_exact(&mut header).unwrap();
        let position = u64::from_be_bytes(header[1..9].try_into().unwrap());
        let size = u32::from_be_bytes(header[9..13].try_into().unwrap()) as usize;
        assert!(size <= CHUNK);
        let mut bytes = vec![0; size]; self.socket.read_exact(&mut bytes).unwrap();
        (header[0], position, bytes)
    }
    fn data(&mut self) -> (u64, Vec<u8>) {
        loop { let (kind, offset, bytes) = self.frame(); if kind == 1 { return (offset, bytes); } }
    }
    fn read(&mut self, count: usize) -> Vec<u8> {
        let mut bytes = Vec::new();
        while bytes.len() < count {
            let (position, data) = self.data(); assert_eq!(self.consumed, position);
            self.consumed += data.len() as u64; bytes.extend(data);
            self.send(2, self.consumed, &[]);
        }
        assert_eq!(count, bytes.len()); bytes
    }
}
impl Drop for Peer { fn drop(&mut self) { shutdown(&self.socket); } }

fn owner() -> (Arc<Replay>, UnixStream) {
    let (app, peer) = UnixStream::pair().unwrap();
    peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    peer.set_write_timeout(Some(Duration::from_secs(2))).unwrap();
    (Replay::new(app).unwrap(), peer)
}
fn detached(owner: &Replay) {
    let state = owner.state.lock().unwrap();
    let (state, _) = owner.changed.wait_timeout_while(state, Duration::from_secs(2), |s| s.link.is_some()).unwrap();
    assert!(state.link.is_none()); assert!(!state.closed);
}

#[test]
fn resumes_exact_offsets_without_reexecuting_input() {
    let (owner, mut app) = owner();
    let mut peer = Peer::connect(&owner, 0, 0, WINDOW);
    peer.send(1, 0, b"once");
    let mut first = [0; 4]; app.read_exact(&mut first).unwrap(); assert_eq!(&first, b"once");
    app.write_all(b"abcdef").unwrap();
    assert_eq!((0, b"abcdef".to_vec()), peer.data());
    drop(peer); detached(&owner);
    let mut next = Peer::connect(&owner, 3, 3, WINDOW);
    assert_eq!(b"def", &next.read(3)[..]);
    next.send(1, 0, b"once"); // A lost ACK must not duplicate previously received bytes.
    next.send(1, 4, b"next");
    app.read_exact(&mut first).unwrap(); assert_eq!(&first, b"next");
    owner.close();
}

#[test]
fn byte_credit_bounds_output_and_resumes_after_consumption() {
    let (owner, mut app) = owner();
    let mut peer = Peer::connect(&owner, 0, 0, 8);
    app.write_all(&vec![b'x'; WINDOW]).unwrap();
    assert_eq!((0, vec![b'x'; 8]), peer.data());
    {
        let state = owner.state.lock().unwrap();
        let (state, _) = owner.changed.wait_timeout_while(state, Duration::from_secs(2), |s| s.produced < WINDOW as u64).unwrap();
        assert_eq!(WINDOW, state.outgoing.len());
        assert_eq!(8, state.link.as_ref().unwrap().cursor);
    }
    peer.send(2, 8, &[]);
    assert_eq!((8, vec![b'x'; 8]), peer.data());
    owner.close();
}

#[test]
fn bad_ack_only_retires_raw_socket_and_keeps_application_owner() {
    let (owner, mut app) = owner();
    let mut peer = Peer::connect(&owner, 0, 0, WINDOW);
    peer.send(2, 500, &[]);
    detached(&owner);
    let mut next = Peer::connect(&owner, 0, 0, WINDOW);
    next.send(1, 0, b"ok");
    let mut data = [0; 2]; app.read_exact(&mut data).unwrap(); assert_eq!(&data, b"ok");
    owner.close();
}

#[test]
fn inconsistent_resume_credit_is_rejected_without_poisoning_owner() {
    let (owner, mut app) = owner();
    app.write_all(b"0123456789").unwrap();
    {
        let state = owner.state.lock().unwrap();
        let (state, _) = owner.changed.wait_timeout_while(state, Duration::from_secs(2), |s| s.produced < 10).unwrap();
        assert_eq!(10, state.produced);
    }
    let (host, guest) = UnixStream::pair().unwrap();
    let copy = owner.clone();
    let task = std::thread::spawn(move || copy.attach(guest));
    let _invalid = Peer::handshake(host, 10, 0, 8);
    assert!(task.join().unwrap().is_err());
    let mut peer = Peer::connect(&owner, 0, 0, WINDOW);
    assert_eq!(b"0123456789", &peer.read(10)[..]);
    owner.close();
}

fn operation(relay: &mut Relay, token: [u8; 16], operation: u8) -> u8 {
    let (mut host, guest) = UnixStream::pair().unwrap();
    let mut request = b"TSR1".to_vec(); request.extend(token); request.push(operation);
    host.write_all(&request).unwrap(); relay.attach(guest).unwrap();
    let mut status = [0]; host.read_exact(&mut status).unwrap(); status[0]
}

#[test]
fn release_is_token_scoped_idempotent_and_closes_the_application() {
    let (owner, mut app) = owner();
    let mut relay = Relay { endpoint: "must-not-open".into(), owner: Some(([7; 16], owner.clone())) };
    assert_eq!(1, operation(&mut relay, [8; 16], 2));
    assert!(!owner.state.lock().unwrap().closed);
    assert_eq!(0, operation(&mut relay, [7; 16], 2));
    assert!(owner.state.lock().unwrap().closed);
    assert_eq!(0, app.read(&mut [0; 1]).unwrap());
    assert_eq!(0, operation(&mut relay, [7; 16], 2));
    assert_eq!(2, operation(&mut relay, [7; 16], 0));
}

#[test]
fn a_different_owner_cannot_take_over_and_expiry_never_replays_mutations() {
    let (owner, _app) = owner();
    let mut relay = Relay { endpoint: "must-not-open".into(), owner: Some(([7; 16], owner.clone())) };
    assert_eq!(1, operation(&mut relay, [8; 16], 1));
    owner.state.lock().unwrap().detached_at = Some(Instant::now() - LEASE);
    relay.expire(); assert!(relay.owner.is_none());
    assert_eq!(2, operation(&mut relay, [7; 16], 0));
}

#[test]
fn release_allows_a_fresh_owner_without_waiting_for_the_lease() {
    use std::os::unix::net::UnixListener;
    let path = std::env::temp_dir().join(format!("term-recovery-{}", std::process::id()));
    let listener = UnixListener::bind(&path).unwrap();
    let (previous, _old_app) = owner();
    let mut relay = Relay { endpoint: path.to_str().unwrap().into(), owner: Some(([7; 16], previous)) };
    assert_eq!(0, operation(&mut relay, [7; 16], 2));
    let (mut host, guest) = UnixStream::pair().unwrap();
    let mut request = b"TSR1".to_vec(); request.extend([9; 16]); request.push(1);
    host.write_all(&request).unwrap();
    let task = std::thread::spawn(move || { relay.attach(guest).unwrap(); relay });
    let mut status = [1]; host.read_exact(&mut status).unwrap(); assert_eq!(0, status[0]);
    let mut peer = Peer::handshake(host, 0, 0, WINDOW);
    let relay = task.join().unwrap();
    let (mut app, _) = listener.accept().unwrap();
    app.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    peer.send(1, 0, b"fresh");
    let mut data = [0; 5]; app.read_exact(&mut data).unwrap(); assert_eq!(&data, b"fresh");
    drop(relay); drop(listener); std::fs::remove_file(path).unwrap();
}

#[test]
fn closure_wakes_application_backpressure_and_link_readers() {
    let (owner, mut app) = owner();
    let mut peer = Peer::connect(&owner, 0, 0, WINDOW);
    peer.send(1, 0, &[b'x'; CHUNK]);
    owner.close();
    // Output/reads must terminate without a heartbeat or lease wait.
    let mut buffer = Vec::new(); app.read_to_end(&mut buffer).unwrap();
    assert!(owner.state.lock().unwrap().closed);
}
