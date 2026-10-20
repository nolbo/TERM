//! A host-CID-only endpoint for one fixed, bounded guest package initialization.
use crate::protocol::{encode_error, ErrorCode, Frame, FrameKind, Service};
use crate::vsock::{VsockConnection, VsockError, VsockListener};
use std::io;
use std::os::unix::process::CommandExt;
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

pub const PORT: u32 = 40_503;
const INITIALIZE: u8 = 0x40;
const MAINTAIN: u8 = 0x41;
const HEALTH: u8 = 0x42;

pub fn serve() -> Result<(), VsockError> {
    let listener = VsockListener::bind(PORT)?;
    loop {
        match listener.accept() {
            Ok(mut connection) => {
                if let Err(error) = initialize(&mut connection) {
                    eprintln!("package initialization connection ended: {error:?}");
                }
            }
            Err(error) => eprintln!("Rejected package initialization peer: {error:?}"),
        }
    }
}

fn initialize(connection: &mut VsockConnection) -> Result<(), VsockError> {
    // An idle host connection must not monopolize the maintenance endpoint.
    let timeout = nix::libc::timeval { tv_sec: 5, tv_usec: 0 };
    unsafe {
        nix::libc::setsockopt(connection.raw_fd(), nix::libc::SOL_SOCKET, nix::libc::SO_RCVTIMEO,
            &timeout as *const _ as *const nix::libc::c_void, std::mem::size_of_val(&timeout) as _);
        nix::libc::setsockopt(connection.raw_fd(), nix::libc::SOL_SOCKET, nix::libc::SO_SNDTIMEO,
            &timeout as *const _ as *const nix::libc::c_void, std::mem::size_of_val(&timeout) as _);
    }
    let request = connection.read_frame()?;
    if !valid_request(&request) {
        return connection.write_frame(&reply(&request, FrameKind::Error,
            encode_error(ErrorCode::Unsupported, "Only initial package refresh is supported")?));
    }
    let maintenance = request.payload == [MAINTAIN];
    if maintenance && !maintenance_boot(&std::fs::read_to_string("/proc/cmdline")?) {
        return connection.write_frame(&reply(&request, FrameKind::Error,
            encode_error(ErrorCode::Unsupported, "A dedicated maintenance boot is required")?));
    }
    let (script, receipt, timeout_seconds) = if request.payload == [HEALTH] {
        ("/usr/local/lib/droid/check-avf-image.sh", "/var/lib/droid/health/complete", 120)
    } else if maintenance {
        ("/usr/local/lib/droid/maintain-avf-image.sh", "/var/lib/droid/maintenance/complete", 1800)
    } else {
        ("/usr/local/lib/droid/initialize-avf-packages.sh", "/var/lib/droid/package-initialization/complete", 340)
    };
    let log = std::fs::File::create("/var/log/term-maintenance.log")?;
    let mut task = Task(Command::new("/bin/sh").arg(script)
        .stdin(Stdio::null()).stdout(log.try_clone()?).stderr(log).process_group(0).spawn()?);
    let started = Instant::now();
    loop {
        if let Some(status) = task.0.try_wait()? {
            let timestamp = std::fs::read_to_string(receipt)
                .ok().and_then(|value| value.trim().parse::<u64>().ok()).filter(|value| *value > 0);
            let response = match (status.success(), timestamp) {
                (true, Some(timestamp)) => reply(&request, FrameKind::Response, timestamp.to_be_bytes().to_vec()),
                _ => {
                    let log = std::fs::read_to_string("/var/log/term-maintenance.log").unwrap_or_default();
                    let detail: String = log.chars().rev().take(1800).collect::<String>().chars().rev().collect();
                    reply(&request, FrameKind::Error, encode_error(ErrorCode::GuestError,
                        &format!("Guest maintenance failed; original environment retained. {detail}"))?)
                },
            };
            return connection.write_frame(&response);
        }
        if started.elapsed() > Duration::from_secs(timeout_seconds) {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "package initialization timed out").into());
        }
        // A closed host connection cancels the process group, including package's
        // methods. A cancelled refresh cannot report host-side completion.
        connection.write_frame(&reply(&request, FrameKind::Stream, request.payload.clone()))?;
        thread::sleep(Duration::from_millis(250));
    }
}

fn valid_request(frame: &Frame) -> bool {
    frame.service == Service::Lifecycle && frame.kind == FrameKind::Request &&
        (frame.payload == [INITIALIZE] || frame.payload == [MAINTAIN] || frame.payload == [HEALTH])
}

fn maintenance_boot(cmdline: &str) -> bool {
    cmdline.split_whitespace().any(|word| matches!(word, "term.maintenance=components" | "term.maintenance=rebase"))
}

fn reply(request: &Frame, kind: FrameKind, payload: Vec<u8>) -> Frame {
    Frame { service: Service::Lifecycle, kind, flags: 0, request_id: request.request_id, payload }
}

struct Task(Child);
impl Drop for Task {
    fn drop(&mut self) {
        if matches!(self.0.try_wait(), Ok(Some(_))) { return; }
        let group = -(self.0.id() as i32);
        unsafe { nix::libc::kill(group, nix::libc::SIGTERM); }
        // The shell may exit before package or a download method. Do not reap it
        // during the grace period: its PID must not be reused before group kill.
        thread::sleep(Duration::from_secs(1));
        unsafe { nix::libc::kill(group, nix::libc::SIGKILL); }
        let _ = self.0.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accepts_only_the_fixed_maintenance_request() {
        let mut frame = Frame { service: Service::Lifecycle, kind: FrameKind::Request,
            flags: 0, request_id: 1, payload: vec![INITIALIZE] };
        assert!(valid_request(&frame));
        frame.payload.extend_from_slice(b" apt upgrade");
        assert!(!valid_request(&frame));
        frame.payload = vec![INITIALIZE];
        frame.service = Service::Session;
        assert!(!valid_request(&frame));
    }
    #[test]
    fn maintenance_requires_exact_boot_mode_and_argument_free_request() {
        assert!(maintenance_boot("root=/dev/vda term.maintenance=components"));
        assert!(maintenance_boot("term.maintenance=rebase quiet"));
        assert!(!maintenance_boot("term.maintenance=rebase-extra"));
        assert!(!maintenance_boot("untrusted=term.maintenance=rebase"));
        let mut frame = Frame { service: Service::Lifecycle, kind: FrameKind::Request,
            flags: 0, request_id: 1, payload: vec![MAINTAIN] };
        assert!(valid_request(&frame));
        frame.payload.push(0);
        assert!(!valid_request(&frame));
    }
    #[test]
    fn dropping_a_task_terminates_its_process_group() {
        let task = Task(Command::new("/bin/sleep").arg("30").process_group(0).spawn().unwrap());
        let pid = task.0.id() as i32;
        drop(task);
        assert_eq!(unsafe { nix::libc::kill(pid, 0) }, -1);
    }

    #[test]
    fn cancellation_kills_descendants_even_when_the_shell_exits_first() {
        use std::io::BufRead;
        let mut task = Task(Command::new("/bin/sh").arg("-c")
            .arg("trap 'exit 0' TERM; sh -c 'trap \"\" TERM; echo $$; exec sleep 30' & wait")
            .stdout(Stdio::piped()).process_group(0).spawn().unwrap());
        let mut pid_line = String::new();
        io::BufReader::new(task.0.stdout.take().unwrap()).read_line(&mut pid_line).unwrap();
        let pid: u32 = pid_line.trim().parse().unwrap();
        drop(task);
        // Orphans can briefly remain as zombies until init reaps them.
        let started = Instant::now();
        loop {
            let stat = std::fs::read_to_string(format!("/proc/{pid}/stat"));
            if stat.is_err() || stat.unwrap().split_once(") ").unwrap().1.starts_with('Z') { break; }
            assert!(started.elapsed() < Duration::from_secs(1), "Updater descendant survived cancellation");
            thread::sleep(Duration::from_millis(10));
        }
    }
}
