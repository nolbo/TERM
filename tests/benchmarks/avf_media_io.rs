// Included only in a temporary graphics_transport.rs test build by avf_media_io.py.
use super::*;

fn new_transfer(media: Media, keys: BTreeMap<char, String>) -> MediaTransfer { __TRANSFER__ }

fn percentile(samples: &mut [f64], percent: usize) -> f64 {
    samples.sort_by(f64::total_cmp);
    samples[(samples.len() - 1) * percent / 100]
}

#[test]
fn media_io() {
    for directory in ["/tmp", "/dev/shm"] {
        let path = format!("{directory}/term-media-io-{}", std::process::id());
        std::fs::write(&path, vec![7; MAX_BYTES]).unwrap();
        let mut opens = Vec::new(); let mut steps = Vec::new();
        for _ in 0..12 {
            let start = std::time::Instant::now();
            let media = read_media('f', &path, 0, 0).unwrap();
            opens.push(start.elapsed().as_secs_f64() * 1e6);
            let mut transfer = new_transfer(media, [('i', "1".into())].into());
            loop {
                let start = std::time::Instant::now();
                let (done, output) = transfer.step().unwrap();
                steps.push(start.elapsed().as_secs_f64() * 1e6);
                std::hint::black_box(output);
                if done { break; }
            }
        }
        println!("BENCH media_io path={directory} open_p50_us={:.3} open_p95_us={:.3} step_p50_us={:.3} step_p95_us={:.3}",
            percentile(&mut opens, 50), percentile(&mut opens, 95), percentile(&mut steps, 50), percentile(&mut steps, 95));
        std::fs::remove_file(path).unwrap();
    }
    struct Slow;
    impl Read for Slow {
        fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
            std::thread::sleep(std::time::Duration::from_millis(50));
            bytes.fill(7); Ok(bytes.len())
        }
    }
    let mut transfer = new_transfer(Media { reader: Box::new(Slow), length: MEDIA_TURN_BYTES,
        cleanup: None }, BTreeMap::new());
    let start = std::time::Instant::now();
    assert!(transfer.step().unwrap().0);
    let elapsed = start.elapsed();
    assert!(elapsed >= std::time::Duration::from_millis(50));
    println!("BENCH injected_slow_read step_ms={:.3} bytes={MEDIA_TURN_BYTES}", elapsed.as_secs_f64() * 1e3);
    struct Short;
    impl Read for Short {
        fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
            std::thread::sleep(std::time::Duration::from_millis(5));
            let count = bytes.len().min(3); bytes[..count].fill(7); Ok(count)
        }
    }
    let mut partial = new_transfer(Media { reader: Box::new(Short), length: 24, cleanup: None }, BTreeMap::new());
    let start = std::time::Instant::now();
    let (done, _) = partial.step().unwrap();
    println!("BENCH injected_short_read first_turn_ms={:.3} done={done}", start.elapsed().as_secs_f64() * 1e3);
}
