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
            nix::pty::ForkptyResult::Parent { child, master } => Ok(Self { master, child }),
            nix::pty::ForkptyResult::Child => child_exec(&launch),
        }
    }

    pub fn read_output(&self, buffer: &mut [u8]) -> Result<usize, PtyError> {
        read(&self.master, buffer).map_err(PtyError::from)
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
        kill(self.child, signal).map_err(PtyError::from)
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
            arguments.push(CString::new(format!("{key}={value}")).map_err(|_| PtyError::InvalidEnvironment)?);
        }
        arguments.push(CString::new(shell).map_err(|_| PtyError::InvalidCommand)?);
        let mut argv = arguments.iter().map(|argument| argument.as_ptr()).collect::<Vec<_>>();
        argv.push(ptr::null());
        Ok(Self { executable, _arguments: arguments, argv })
    }
}

fn child_exec(launch: &PtyLaunch) -> ! {
    unsafe {
        nix::libc::execv(launch.executable.as_ptr(), launch.argv.as_ptr());
        nix::libc::_exit(127)
    }
}
