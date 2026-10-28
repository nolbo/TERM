// Included inside server.rs only by avf_sessiond_overhead.py's temporary builds.
// Counts guest allocations, not Android/VM memory or end-to-end terminal latency.
use super::*;
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::Relaxed};

struct Counter;
static TRACK: AtomicBool = AtomicBool::new(false);
static CALLS: AtomicUsize = AtomicUsize::new(0);
static LIVE: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);
fn allocated(size: usize) {
    CALLS.fetch_add(1, Relaxed);
    let live = LIVE.fetch_add(size, Relaxed) + size;
    PEAK.fetch_max(live, Relaxed);
}
unsafe impl GlobalAlloc for Counter {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let result = System.alloc(layout);
        if !result.is_null() && TRACK.load(Relaxed) { allocated(layout.size()); }
        result
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        if TRACK.load(Relaxed) { LIVE.fetch_sub(layout.size(), Relaxed); }
        System.dealloc(ptr, layout)
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let result = System.realloc(ptr, layout, size);
        if !result.is_null() && TRACK.load(Relaxed) {
            LIVE.fetch_sub(layout.size(), Relaxed); allocated(size);
        }
        result
    }
}
#[global_allocator]
static ALLOCATOR: Counter = Counter;

fn sample(name: &str, rounds: usize, run: impl FnMut()) {
    sample_with_cleanup(name, rounds, run, || {});
}
fn sample_with_cleanup(name: &str, rounds: usize, mut run: impl FnMut(), mut cleanup: impl FnMut()) {
    run(); cleanup(); // Warm persistent capacities before counting transient allocations.
    CALLS.store(0, Relaxed); LIVE.store(0, Relaxed); PEAK.store(0, Relaxed);
    TRACK.store(true, Relaxed);
    run();
    TRACK.store(false, Relaxed);
    let calls = CALLS.load(Relaxed); let peak = PEAK.load(Relaxed);
    assert_eq!(LIVE.load(Relaxed), 0, "measured operation must release its transient allocations");
    cleanup();
    let start = std::time::Instant::now();
    for _ in 0..rounds { run(); cleanup(); }
    println!("BENCH {name} allocations={calls} peak_bytes={peak} mean_us={:.3}",
        start.elapsed().as_secs_f64() * 1e6 / rounds as f64);
}

#[test]
#[ignore]
fn overhead() {
    let payload = vec![7; 8192];
    sample("output_8k", 20_000, || {
        let event = SessionEvent::Output { session_id: "session-1", bytes: &payload };
        let frame = __ENCODE_EVENT__;
        std::hint::black_box(frame);
    });
    // Actual healthy guest output path. Peer decoding is outside allocation
    // counting; elapsed time includes the local socket round trip on both builds.
    let mut live = SessionServer::default();
    let id = live.sessions.create_shell("/bin/sh", "/", 24, 80).id.clone();
    let (mut peer, socket) = std::os::unix::net::UnixStream::pair().unwrap();
    peer.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
    let mut client = Client::new(VsockConnection::new(std::fs::File::from(std::os::fd::OwnedFd::from(socket))).unwrap()).unwrap();
    client.known.insert(id.clone()); live.client = Some(client);
    sample_with_cleanup("active_output_8k", 10_000, || {
        live.process_output(&id, &payload); live.service_client();
    }, || { crate::vsock::read_frame_from(&mut peer).unwrap(); });

    let mut server = SessionServer::default();
    for _ in 0..8 {
        let id = server.sessions.create_shell("/bin/sh", "/", 24, 80).id.clone();
        let args = ["/bin/sh", "-c", "exec sleep 30"];
        let argv = format!("3:{}", args.iter().map(|a| format!("{}:{}", a.len(), a)).collect::<String>());
        server.sessions.start_shell(&id, &[("DROID_SESSION_ARGV".into(), argv)]).unwrap();
    }
    sample("idle_turn_8_sessions", 20_000, || server.turn(None, 0).unwrap());
    let ids: Vec<_> = server.sessions.list().map(|s| s.id.clone()).collect();
    for id in ids { server.sessions.terminate(&id, nix::sys::signal::Signal::SIGKILL).unwrap(); }

    let file = __MEDIA_PATH__;
    std::fs::write(file, vec![7; 6 * 1024 * 1024]).unwrap();
    let command = __MEDIA_COMMAND__;
    let mut graphics = GraphicsTransport::default();
    sample("graphic_6m", 5, || {
        __CONVERT_MEDIA__
    });
    std::fs::remove_file(file).unwrap();
}
