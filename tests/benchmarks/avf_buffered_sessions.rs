
// Isolate scheduler scans with empty staged parser work and empty live tails.
// This is deliberately not a complete-image or end-to-end AVF benchmark.
#[test]
#[ignore]
fn buffered_sessions() {
    for count in [1, 8, 32, 64] {
        let mut server = SessionServer::default();
        let mut ids = Vec::new();
        for _ in 0..count {
            let response = server.dispatch(SessionRequest::Create { shell: "/bin/sh".into(), cwd: "/".into(),
                env: [("DROID_SESSION_ARGV".into(), "3:7:/bin/sh2:-c13:exec sleep 30".into())].into(), rows: 24, cols: 80 }, &mut None).unwrap();
            let SessionResponse::Created(session) = response else { panic!("create response") };
            server.output.tails.entry(session.id.clone()).or_default();
            ids.push(session.id);
        }
        let (_peer, socket) = std::os::unix::net::UnixStream::pair().unwrap();
        server.client = Some(Client::new(VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(socket))).unwrap()).unwrap());
        let run = |server: &mut SessionServer| {
            for id in &ids { server.staged_output.insert(id.clone(), Vec::new()); }
            server.turn(None, 0).unwrap();
            assert!(server.client.is_some());
        };
        for _ in 0..20 { run(&mut server); }
        let start = std::time::Instant::now();
        for _ in 0..2_000 { run(&mut server); }
        println!("BENCH buffered_{count} mean_us={:.3}", start.elapsed().as_secs_f64() * 1e6 / 2_000.0);
        for id in ids { server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL).unwrap(); }
    }
}
