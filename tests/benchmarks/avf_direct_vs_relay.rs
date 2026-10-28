//! Opt-in same-host comparison: identical Rust wire peer and echo application,
//! direct sockets versus the baseline Rust relay. See docs/AVF_NOTES.md for the
//! pinned historical checkout harness; no relay code is linked into current guests.
use super::*;
use std::os::unix::net::UnixListener;

#[test]
#[ignore = "local socket benchmark; not an AVF device measurement"]
fn native_and_direct_transport_benchmark() {
    const WARMUP: usize = 100;
    const ROUNDS: usize = 2000;
    const BULK: usize = 8 * 1024 * 1024;
    for native in (0..5).flat_map(|_| [false, true]) {
        let root = std::env::temp_dir().join(format!("term-relay-bench-{}-{native}", std::process::id()));
        std::fs::create_dir(&root).unwrap();
        let endpoint = root.join("app.sock");
        let raw = root.join("raw.sock");
        let app_listener = UnixListener::bind(&endpoint).unwrap();
        let (start_bulk, bulk_ready) = std::sync::mpsc::channel();
        let application = std::thread::spawn(move || {
            let (mut app, _) = app_listener.accept().unwrap();
            app.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
            app.set_write_timeout(Some(Duration::from_secs(10))).unwrap();
            let mut byte = [0];
            for _ in 0..WARMUP + ROUNDS {
                app.read_exact(&mut byte).unwrap(); app.write_all(&byte).unwrap();
            }
            bulk_ready.recv_timeout(Duration::from_secs(10)).unwrap();
            app.write_all(&vec![b'z'; BULK]).unwrap();
            // Keep the application owner alive until explicit host release.
            assert_eq!(0, app.read(&mut byte).unwrap());
        });
        if !native {
            let mut host = UnixStream::connect(&endpoint).unwrap();
            host.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
            let mut byte = [0];
            for _ in 0..WARMUP { host.write_all(b"a").unwrap(); host.read_exact(&mut byte).unwrap(); }
            let started = Instant::now();
            for _ in 0..ROUNDS { host.write_all(b"b").unwrap(); host.read_exact(&mut byte).unwrap(); assert_eq!(byte, [b'b']); }
            let round_trip_us = started.elapsed().as_secs_f64() * 1_000_000.0 / ROUNDS as f64;
            let started = Instant::now(); start_bulk.send(()).unwrap();
            let mut bulk = vec![0; BULK]; host.read_exact(&mut bulk).unwrap();
            assert!(bulk.iter().all(|byte| *byte == b'z'));
            let mib_per_second = 8.0 / started.elapsed().as_secs_f64();
            println!("relay=direct echo_mean_us={round_trip_us:.2} bulk_mib_per_second={mib_per_second:.2}");
            drop(host); application.join().unwrap(); std::fs::remove_dir_all(root).unwrap();
            continue;
        }
        let listener = UnixListener::bind(&raw).unwrap();
        let endpoint = endpoint.to_str().unwrap().to_owned();
        let server = std::thread::spawn(move || {
            let mut relay = Relay { endpoint, owner: None };
            for _ in 0..2 { relay.attach(listener.accept().unwrap().0).unwrap(); }
        });
        let started = Instant::now();
        let mut host = loop {
            match UnixStream::connect(&raw) {
                Ok(stream) => break stream,
                Err(_) => {
                    assert!(started.elapsed() < Duration::from_secs(5));
                    std::thread::sleep(Duration::from_millis(5));
                }
            }
        };
        host.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut request = b"TSR1".to_vec(); request.extend([5; 16]); request.push(1);
        host.write_all(&request).unwrap();
        let mut status = [1]; host.read_exact(&mut status).unwrap(); assert_eq!(0, status[0]);
        let mut peer = Peer::handshake(host, 0, 0, WINDOW);
        for offset in 0..WARMUP {
            peer.send(1, offset as u64, b"a"); assert_eq!(b"a", &peer.read(1)[..]);
        }
        let started = Instant::now();
        for offset in WARMUP..WARMUP + ROUNDS {
            peer.send(1, offset as u64, b"b"); assert_eq!(b"b", &peer.read(1)[..]);
        }
        let round_trip_us = started.elapsed().as_secs_f64() * 1_000_000.0 / ROUNDS as f64;
        let started = Instant::now(); start_bulk.send(()).unwrap();
        assert!(peer.read(BULK).iter().all(|byte| *byte == b'z'));
        let mib_per_second = 8.0 / started.elapsed().as_secs_f64();
        println!("relay=native echo_mean_us={round_trip_us:.2} bulk_mib_per_second={mib_per_second:.2}");
        let mut release = UnixStream::connect(&raw).unwrap();
        request[20] = 2; release.write_all(&request).unwrap(); release.read_exact(&mut status).unwrap();
        assert_eq!(0, status[0]);
        application.join().unwrap();
        server.join().unwrap();
        drop(peer);
        std::fs::remove_dir_all(root).unwrap();
    }
}
