//! Linux PTY ownership. This module is intentionally guest-only: neither the
//! Android Activity nor a vsock client ever receives the master file descriptor.

use std::ffi::CString;
use std::os::fd::{AsRawFd, OwnedFd};
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
}

impl From<Errno> for PtyError {
    fn from(error: Errno) -> Self { Self::Nix(error) }
}

#[derive(Debug)]
pub struct PtyProcess {
    master: OwnedFd,
    child: Pid,
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
                if flags < 0 || unsafe { nix::libc::fcntl(fd, nix::libc::F_SETFL, flags | nix::libc::O_NONBLOCK) } < 0 {
                    let _ = kill(child, Signal::SIGKILL);
                    let _ = waitpid(child, None);
                    return Err(PtyError::Nix(Errno::last()));
                }
                Ok(Self { master, child })
            },
            nix::pty::ForkptyResult::Child => child_exec(&launch),
        }
    }

    pub fn read_output(&self, buffer: &mut [u8]) -> Result<usize, PtyError> {
        read(self.master.as_raw_fd(), buffer).map_err(PtyError::from)
    }

    pub fn write_input(&self, bytes: &[u8]) -> Result<usize, PtyError> {
        write(&self.master, bytes).map_err(PtyError::from)
    }

    pub fn resize(&self, rows: u16, cols: u16) -> Result<(), PtyError> {
        if rows == 0 || cols == 0 { return Err(PtyError::InvalidEnvironment); }
        let winsize = Winsize { ws_row: rows, ws_col: cols, ws_xpixel: 0, ws_ypixel: 0 };
        let result = unsafe {
            nix::libc::ioctl(self.master.as_raw_fd(), nix::libc::TIOCSWINSZ, &winsize)
        };
        if result == -1 { return Err(PtyError::Nix(Errno::last())); }
        Ok(())
    }

    pub fn terminate(&self, signal: Signal) -> Result<(), PtyError> {
        match kill(self.child, signal) {
            Ok(()) | Err(Errno::ESRCH) => Ok(()),
            Err(error) => Err(PtyError::from(error)),
        }
    }

    pub fn poll_exit(&self) -> Result<Option<i32>, PtyError> {
        match waitpid(self.child, Some(nix::sys::wait::WaitPidFlag::WNOHANG)).map_err(PtyError::from)? {
            WaitStatus::StillAlive => Ok(None),
            WaitStatus::Exited(_, status) => Ok(Some(status)),
            WaitStatus::Signaled(_, signal, _) => Ok(Some(128 + signal as i32)),
            _ => Ok(None),
        }
    }

    pub fn wait_for_exit(self) -> Result<i32, PtyError> {
        loop {
            match waitpid(self.child, None).map_err(PtyError::from)? {
                WaitStatus::Exited(_, status) => return Ok(status),
                WaitStatus::Signaled(_, signal, _) => return Ok(128 + signal as i32),
                _ => continue,
            }
        }
    }

    pub fn raw_fd(&self) -> std::os::fd::RawFd { self.master.as_raw_fd() }
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
        nix::libc::execv(launch.executable.as_ptr(), launch.argv.as_ptr());
        nix::libc::_exit(127)
    }
}
