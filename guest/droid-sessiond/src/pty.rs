//! Linux PTY ownership. This module is intentionally guest-only: neither the
//! Android Activity nor a vsock client ever receives the master file descriptor.

use std::ffi::CString;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::raw::c_char;
use std::ptr;

use nix::errno::Errno;
use nix::pty::{forkpty, Winsize};
use nix::sys::signal::{kill, Signal};
use nix::sys::wait::{waitpid, WaitStatus};
use nix::unistd::{read, write, Pid};

#[derive(Debug)]
pub enum PtyError {
    Nix(Errno),
    SessionNotFound,
    SessionNotStarted,
    InvalidCommand,
    InvalidEnvironment,
    Capacity,
}

impl From<Errno> for PtyError {
    fn from(error: Errno) -> Self { Self::Nix(error) }
}

#[derive(Debug)]
pub struct PtyProcess {
    master: Option<OwnedFd>,
    child: Pid,
    exit: Option<OwnedFd>,
    output_eof: bool,
}

impl PtyProcess {
    pub fn spawn(
        shell: &str,
        cwd: &str,
        environment: &[(String, String)],
        rows: u16,
        cols: u16,
    ) -> Result<Self, PtyError> {
        if shell.is_empty() || cwd.is_empty() || shell.as_bytes().contains(&0) || cwd.as_bytes().contains(&0) {
            return Err(PtyError::InvalidCommand);
        }
        if rows == 0 || cols == 0 || environment.iter().any(|(key, _)| key.is_empty() || key.contains('=') || key.contains('\0')) {
            return Err(PtyError::InvalidEnvironment);
        }

        let launch = PtyLaunch::new(shell, cwd, environment)?;
        let winsize = Winsize { ws_row: rows, ws_col: cols, ws_xpixel: 0, ws_ypixel: 0 };
        // forkpty creates a controlling terminal for the child and retains the
        // master endpoint exclusively in this daemon.
        let fork = unsafe { forkpty(Some(&winsize), None) }.map_err(PtyError::from)?;
        match fork {
            nix::pty::ForkptyResult::Parent { child, master } => {
                let fd = master.as_raw_fd();
                let flags = unsafe { nix::libc::fcntl(fd, nix::libc::F_GETFL) };
                if flags < 0
                    || unsafe { nix::libc::fcntl(fd, nix::libc::F_SETFL, flags | nix::libc::O_NONBLOCK) } < 0
                    || unsafe { nix::libc::fcntl(fd, nix::libc::F_SETFD, nix::libc::FD_CLOEXEC) } < 0 {
                    let error = Errno::last();
                    kill_and_reap(child);
                    return Err(error.into());
                }
                // AVF's Linux 6.12 supports pidfd_open (since Linux 5.3). It
                // wakes the same poll loop even when a descendant holds the PTY.
                let exit = unsafe { nix::libc::syscall(nix::libc::SYS_pidfd_open, child.as_raw(), 0) };
                if exit < 0 {
                    let error = Errno::last();
                    kill_and_reap(child);
                    return Err(error.into());
                }
                Ok(Self { master: Some(master), child, exit: Some(unsafe { OwnedFd::from_raw_fd(exit as _) }), output_eof: false })
            },
            nix::pty::ForkptyResult::Child => child_exec(&launch),
        }
    }

    pub fn read_output(&self, buffer: &mut [u8]) -> Result<usize, PtyError> {
        let fd = self.raw_fd().ok_or(PtyError::SessionNotStarted)?;
        loop {
            match read(fd, buffer) {
                Err(Errno::EINTR) => continue,
                result => return result.map_err(PtyError::from),
            }
        }
    }

    pub fn write_input(&self, bytes: &[u8]) -> Result<usize, PtyError> {
        if self.output_eof { return Err(PtyError::SessionNotStarted); }
        write(self.master.as_ref().ok_or(PtyError::SessionNotStarted)?, bytes).map_err(PtyError::from)
    }

    pub fn resize(&self, rows: u16, cols: u16) -> Result<(), PtyError> {
        if rows == 0 || cols == 0 { return Err(PtyError::InvalidEnvironment); }
        let winsize = Winsize { ws_row: rows, ws_col: cols, ws_xpixel: 0, ws_ypixel: 0 };
        let result = unsafe {
            nix::libc::ioctl(self.raw_fd().ok_or(PtyError::SessionNotStarted)?, nix::libc::TIOCSWINSZ, &winsize)
        };
        if result == -1 { return Err(PtyError::Nix(Errno::last())); }
        Ok(())
    }

    pub fn terminate(&self, signal: Signal) -> Result<(), PtyError> {
        if self.exit.is_none() { return Ok(()); } // Never signal a reaped/reused PID.
        match kill(self.child, signal) {
            Ok(()) | Err(Errno::ESRCH) => Ok(()),
            Err(error) => Err(PtyError::from(error)),
        }
    }

    pub fn poll_exit(&mut self) -> Result<Option<i32>, PtyError> {
        if self.exit.is_none() { return Ok(None); }
        let code = match waitpid(self.child, Some(nix::sys::wait::WaitPidFlag::WNOHANG)).map_err(PtyError::from)? {
            WaitStatus::Exited(_, status) => Some(status),
            WaitStatus::Signaled(_, signal, _) => Some(128 + signal as i32),
            _ => None,
        };
        if code.is_some() { self.exit.take(); }
        Ok(code)
    }

    pub fn raw_fd(&self) -> Option<RawFd> { if self.output_eof { None } else { self.master.as_ref().map(AsRawFd::as_raw_fd) } }
    pub fn mark_output_eof(&mut self) { self.output_eof = true; }
    pub fn exit_fd(&self) -> Option<RawFd> { self.exit.as_ref().map(AsRawFd::as_raw_fd) }
    pub fn close_output(&mut self) { self.master.take(); }
}

fn kill_and_reap(child: Pid) {
    let _ = kill(child, Signal::SIGKILL);
    while matches!(waitpid(child, None), Err(Errno::EINTR)) {}
}

/// All allocations and validation happen before fork. The child invokes only
/// `execv` then `_exit`, which is safe for a multithreaded daemon.
struct PtyLaunch {
    executable: CString,
    _arguments: Vec<CString>,
    argv: Vec<*const c_char>,
}

impl PtyLaunch {
    fn new(shell: &str, cwd: &str, environment: &[(String, String)]) -> Result<Self, PtyError> {
        // Debian's coreutils `env` changes cwd and applies assignments without
        // passing untrusted data through a shell parser.
        let executable = CString::new("/usr/bin/env").expect("static path has no NUL");
        let mut arguments = vec![
            CString::new("env").expect("static argument has no NUL"),
            CString::new("-C").expect("static argument has no NUL"),
            CString::new(cwd).map_err(|_| PtyError::InvalidCommand)?,
        ];
        for (key, value) in environment {
            if key == "DROID_SESSION_ARGV" { continue; }
            arguments.push(CString::new(format!("{key}={value}")).map_err(|_| PtyError::InvalidEnvironment)?);
        }
        if let Some((_, encoded)) = environment.iter().find(|(key, _)| key == "DROID_SESSION_ARGV") {
            for arg in decode_command(encoded)? {
                arguments.push(CString::new(arg).map_err(|_| PtyError::InvalidCommand)?);
            }
        } else {
            arguments.push(CString::new(shell).map_err(|_| PtyError::InvalidCommand)?);
        }
        let mut argv = arguments.iter().map(|argument| argument.as_ptr()).collect::<Vec<_>>();
        argv.push(ptr::null());
        Ok(Self { executable, _arguments: arguments, argv })
    }
}

// Same length-prefixed UTF-8 envelope as the Android SessionCommand encoder.
fn decode_command(encoded: &str) -> Result<Vec<&str>, PtyError> {
    if encoded.len() > 4096 { return Err(PtyError::InvalidCommand); }
    fn number<'a>(rest: &mut &'a str) -> Result<usize, PtyError> {
        let (digits, tail) = rest.split_once(':').ok_or(PtyError::InvalidCommand)?;
        if digits.is_empty() || digits.len() > 5 || !digits.bytes().all(|b| b.is_ascii_digit()) {
            return Err(PtyError::InvalidCommand);
        }
        let value = digits.parse().map_err(|_| PtyError::InvalidCommand)?;
        *rest = tail;
        Ok(value)
    }
    let mut rest = encoded;
    let count = number(&mut rest)?;
    if count == 0 || count > 64 { return Err(PtyError::InvalidCommand); }
    let mut result = Vec::with_capacity(count);
    for _ in 0..count {
        let size = number(&mut rest)?;
        if size > rest.len() || !rest.is_char_boundary(size) { return Err(PtyError::InvalidCommand); }
        let (arg, tail) = rest.split_at(size);
        if arg.as_bytes().contains(&0) { return Err(PtyError::InvalidCommand); }
        result.push(arg);
        rest = tail;
    }
    if !rest.is_empty() || result[0].is_empty() || result[0].starts_with('-') || result[0].contains('=') {
        return Err(PtyError::InvalidCommand);
    }
    Ok(result)
}

#[cfg(test)]
mod command_tests {
    use super::decode_command;
    #[test]
    fn preserves_arguments_and_rejects_truncation() {
        assert_eq!(decode_command("3:4:echo3:a b6:한글").unwrap(), vec!["echo", "a b", "한글"]);
        for invalid in ["0:", "1:9:a", "1:0:", "1:1:한", "1:6:--help", "1:3:A=B", "1:1:aextra"] {
            assert!(decode_command(invalid).is_err(), "{invalid}");
        }
    }
}

fn child_exec(launch: &PtyLaunch) -> ! {
    unsafe {
        // Only async-signal-safe libc calls after fork. Rust ignores SIGPIPE;
        // a shell/CLI must inherit normal Linux signal behavior, not the daemon's.
        let mut action: nix::libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = nix::libc::SIG_DFL;
        nix::libc::sigemptyset(&mut action.sa_mask);
        for signal in 1..=64 {
            // SIGKILL/SIGSTOP and libc-reserved signals cannot be changed.
            nix::libc::sigaction(signal, &action, std::ptr::null_mut());
        }
        let mut mask: nix::libc::sigset_t = std::mem::zeroed();
        nix::libc::sigemptyset(&mut mask);
        if nix::libc::sigprocmask(nix::libc::SIG_SETMASK, &mask, std::ptr::null_mut()) != 0 {
            nix::libc::_exit(127);
        }
        nix::libc::execv(launch.executable.as_ptr(), launch.argv.as_ptr());
        nix::libc::_exit(127)
    }
}

#[cfg(test)] mod signal_tests {
    use super::*;
    #[test] fn child_restores_sigpipe_default() {
        let script = "kill -PIPE $$; printf PIPE_IGNORED";
        let args = ["/bin/sh", "-c", script];
        let encoded = format!("3:{}", args.iter().map(|a| format!("{}:{}", a.len(), a)).collect::<String>());
        let mut child = PtyProcess::spawn("/bin/sh", "/", &[("DROID_SESSION_ARGV".into(), encoded)], 24, 80).unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        let code = loop {
            if let Some(code) = child.poll_exit().unwrap() { break code; }
            assert!(std::time::Instant::now() < deadline);
            std::thread::sleep(std::time::Duration::from_millis(2));
        };
        let mut output = [0; 128]; let n = child.read_output(&mut output).unwrap_or(0);
        child.close_output();
        assert_eq!(code, 128 + nix::libc::SIGPIPE);
        assert!(!output[..n].ends_with(b"PIPE_IGNORED"));
    }
}
