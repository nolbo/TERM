//! Transport health check, not a signing/IPA-install product feature.
use std::{io::{Read, Write}, os::fd::AsRawFd};
use terminal_iphone_connector::{Connector, authenticate_host};

#[tokio::main(worker_threads = 2)]
async fn main() {
    unsafe { libc::umask(0o077); libc::prctl(libc::PR_SET_DUMPABLE, 0); libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0); }
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 3 || args[1] != "probe" { println!("{{\"error\":\"WORKER_PROTOCOL\"}}"); std::process::exit(1); }
    let mut token = [0u8; 37];
    if std::io::stdin().read_exact(&mut token).is_err() { std::process::exit(1); }
    let result = std::str::from_utf8(&token).ok().and_then(|token| authenticate_host(&args[2], token).ok()); token.fill(0);
    let Some((owner, fd)) = result else { println!("{{\"error\":\"WORKER_AUTHENTICATION\"}}"); std::process::exit(1); };
    std::thread::spawn(move || { let mut byte = 0u8; unsafe { libc::recv(owner.as_raw_fd(), &mut byte as *mut _ as _, 1, 0); libc::_exit(1); } });
    let connector = Connector::new(fd);
    let result = tokio::time::timeout(std::time::Duration::from_secs(15), async {
        // Independent streams prove the owner can supply multiple logical clients.
        let mut first = connector.connection().await?;
        let mut second = connector.connection().await?;
        let _ = first.get_devices().await?;
        let _ = second.get_buid().await?;
        Ok::<_, idevice::IdeviceError>(())
    }).await;
    if matches!(result, Ok(Ok(()))) {
        println!("{{\"ok\":true,\"privateConnector\":true,\"publicMuxSocket\":false}}");
        let _ = std::io::stdout().flush();
    } else { println!("{{\"error\":\"WORKER_PROTOCOL\"}}"); std::process::exit(1); }
}
