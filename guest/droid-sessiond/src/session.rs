//! Session metadata and PTYs are owned together by the guest daemon. UI client
//! attachment is only a registry operation; it never owns a shell process.

use std::collections::{BTreeMap, BTreeSet};
use std::os::fd::RawFd;
use std::time::{SystemTime, UNIX_EPOCH};

use nix::sys::signal::Signal;

use crate::pty::{PtyError, PtyProcess};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionState {
    Created,
    Running,
    Exited(i32),
}

#[derive(Debug)]
pub struct Session {
    pub id: String,
    pub title: String,
    pub command: String,
    pub cwd: String,
    pub created_at_ms: u128,
    pub rows: u16,
    pub cols: u16,
    pub state: SessionState,
    pty: Option<PtyProcess>,
    attached_clients: u32,
}

#[derive(Default)]
pub struct SessionRegistry {
    sessions: BTreeMap<String, Session>,
    running: BTreeSet<String>,
    next_id: u64,
    retired: Vec<PtyProcess>,
}

impl SessionRegistry {
    pub fn create_shell(&mut self, shell: &str, cwd: &str, rows: u16, cols: u16) -> &Session {
        self.next_id += 1;
        let id = format!("session-{}", self.next_id);
        let session = Session {
            id: id.clone(),
            title: "Shell".into(),
            command: shell.into(),
            cwd: cwd.into(),
            created_at_ms: SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis(),
            rows,
            cols,
            state: SessionState::Created,
            pty: None,
            attached_clients: 0,
        };
        self.sessions.insert(id.clone(), session);
        self.sessions.get(&id).expect("new session must exist")
    }

    pub fn get(&self, id: &str) -> Option<&Session> {
        self.sessions.get(id)
    }

    pub fn list(&self) -> impl Iterator<Item = &Session> {
        self.sessions.values()
    }

    /// Sessions stay active until both child exit and the final output drain complete.
    pub fn running(&self) -> impl Iterator<Item = &Session> {
        self.running.iter().filter_map(|id| self.sessions.get(id))
    }

    pub fn start_shell(&mut self, id: &str, environment: &[(String, String)]) -> Result<(), PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        if matches!(session.state, SessionState::Exited(_)) { return Err(PtyError::SessionNotStarted); }
        if session.pty.is_none() {
            let spawned = PtyProcess::spawn(
                &session.command,
                &session.cwd,
                environment,
                session.rows,
                session.cols,
            );
            session.pty = Some(match spawned {
                Ok(pty) => pty,
                Err(error) => { self.sessions.remove(id); return Err(error); }
            });
            session.state = SessionState::Running;
            self.running.insert(id.to_owned());
        }
        Ok(())
    }

    pub fn attach(&mut self, id: &str) -> Result<(), PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        if session.state == SessionState::Created { return Err(PtyError::SessionNotStarted); }
        session.attached_clients = session.attached_clients.saturating_add(1);
        Ok(())
    }

    pub fn detach(&mut self, id: &str) -> Result<(), PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        session.attached_clients = session.attached_clients.saturating_sub(1);
        Ok(())
    }

    pub fn write_input(&self, id: &str, bytes: &[u8]) -> Result<usize, PtyError> {
        let session = self.sessions.get(id).ok_or(PtyError::SessionNotFound)?;
        session.pty.as_ref().ok_or(PtyError::SessionNotStarted)?.write_input(bytes)
    }

    pub fn read_output(&self, id: &str, buffer: &mut [u8]) -> Result<usize, PtyError> {
        let session = self.sessions.get(id).ok_or(PtyError::SessionNotFound)?;
        session.pty.as_ref().ok_or(PtyError::SessionNotStarted)?.read_output(buffer)
    }

    pub fn resize(&mut self, id: &str, rows: u16, cols: u16) -> Result<(), PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        let pty = session.pty.as_ref().ok_or(PtyError::SessionNotStarted)?;
        pty.resize(rows, cols)?;
        session.rows = rows;
        session.cols = cols;
        Ok(())
    }

    pub fn terminate(&mut self, id: &str, signal: Signal) -> Result<(), PtyError> {
        let session = self.sessions.get(id).ok_or(PtyError::SessionNotFound)?;
        if let Some(pty) = &session.pty { pty.terminate(signal)?; }
        self.running.remove(id);
        if let Some(mut pty) = self.sessions.remove(id).and_then(|session| session.pty) {
            pty.close_output();
            if pty.exit_fd().is_some() { self.retired.push(pty); }
        }
        Ok(())
    }

    pub fn exit_fd(&self, id: &str) -> Option<RawFd> { self.sessions.get(id)?.pty.as_ref()?.exit_fd() }
    pub fn retired_fds(&self) -> impl Iterator<Item = RawFd> + '_ { self.retired.iter().filter_map(PtyProcess::exit_fd) }
    pub fn reap_retired(&mut self) {
        self.retired.retain_mut(|pty| match pty.poll_exit() {
            Ok(Some(_)) | Err(PtyError::Nix(nix::errno::Errno::ECHILD)) => false,
            _ => true,
        });
    }

    pub fn mark_output_eof(&mut self, id: &str) {
        if let Some(pty) = self.sessions.get_mut(id).and_then(|session| session.pty.as_mut()) {
            // Stop polling EOF without closing the master: closing it while the
            // leader is alive would deliver SIGHUP and change its exit status.
            pty.mark_output_eof();
        }
    }

    pub fn close_pty(&mut self, id: &str) {
        if let Some(pty) = self.sessions.get_mut(id).and_then(|session| session.pty.as_mut()) { pty.close_output(); }
    }

    pub fn close_output(&mut self, id: &str) {
        if let Some(session) = self.sessions.get_mut(id) {
            if let Some(pty) = &mut session.pty { pty.close_output(); }
            if matches!(session.state, SessionState::Exited(_)) {
                session.pty.take(); self.running.remove(id);
            }
        }
    }

    pub fn output_fd(&self, id: &str) -> Result<RawFd, PtyError> {
        let session = self.sessions.get(id).ok_or(PtyError::SessionNotFound)?;
        session.pty.as_ref().and_then(PtyProcess::raw_fd).ok_or(PtyError::SessionNotStarted)
    }

    pub fn poll_exit(&mut self, id: &str) -> Result<Option<i32>, PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        if let SessionState::Exited(code) = session.state { return Ok(Some(code)); }
        let code = session.pty.as_mut().ok_or(PtyError::SessionNotStarted)?.poll_exit()?;
        if let Some(exit_code) = code {
            session.state = SessionState::Exited(exit_code);
            // Reap immediately; inherited slave descriptors cannot keep a zombie.
            // The server then drains the bounded final tail and closes the master.
        }
        Ok(code)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn await_exit(registry: &mut SessionRegistry, id: &str) -> i32 {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        loop {
            if let Some(code) = registry.poll_exit(id).unwrap() { registry.close_output(id); return code; }
            assert!(std::time::Instant::now() < deadline, "child did not exit");
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
    }

    #[test]
    fn failed_launch_leaves_no_created_session() {
        let mut registry = SessionRegistry::default();
        let id = registry.create_shell("/bin/sh", "/", 24, 80).id.clone();
        assert!(registry.start_shell(&id, &[("DROID_SESSION_ARGV".into(), "invalid".into())]).is_err());
        assert_eq!(0, registry.list().count());
        assert_eq!(0, registry.running().count());
        assert_eq!(0, registry.retired_fds().count());
    }

    #[test]
    fn natural_exit_releases_pty_after_tail_output_and_preserves_exit_history() {
        let mut registry = SessionRegistry::default();
        let id = registry.create_shell("/bin/sh", "/", 24, 80).id.clone();
        let args = ["/bin/sh", "-c", "printf tail-output; exit 7"];
        let encoded = format!("3:{}", args.iter().map(|arg| format!("{}:{}", arg.len(), arg)).collect::<String>());
        registry.start_shell(&id, &[("DROID_SESSION_ARGV".into(), encoded)]).unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        let mut output = Vec::new();
        let mut buffer = [0; 64];
        loop {
            assert!(std::time::Instant::now() < deadline, "PTY did not close");
            match registry.read_output(&id, &mut buffer) {
                Ok(0) | Err(PtyError::Nix(nix::errno::Errno::EIO)) => break,
                Ok(count) => output.extend_from_slice(&buffer[..count]),
                Err(PtyError::Nix(nix::errno::Errno::EAGAIN | nix::errno::Errno::EINTR)) =>
                    std::thread::sleep(std::time::Duration::from_millis(2)),
                Err(error) => panic!("unexpected PTY read: {error:?}"),
            }
        }
        assert_eq!(b"tail-output", output.as_slice());
        assert_eq!(7, await_exit(&mut registry, &id));
        assert_eq!(Some(7), registry.poll_exit(&id).unwrap());
        assert!(registry.get(&id).unwrap().pty.is_none());
        assert!(matches!(registry.output_fd(&id), Err(PtyError::SessionNotStarted)));
        assert_eq!(0, registry.running().count());
        assert_eq!(1, registry.list().count());
        registry.attach(&id).unwrap();
        registry.detach(&id).unwrap();
        assert!(matches!(registry.start_shell(&id, &[]), Err(PtyError::SessionNotStarted)));
        assert_eq!(SessionState::Exited(7), registry.get(&id).unwrap().state);
        registry.terminate(&id, Signal::SIGKILL).unwrap();
        assert_eq!(0, registry.list().count());
    }

    #[test]
    fn exited_history_never_retains_ptys_or_enters_the_running_poll_set() {
        let mut registry = SessionRegistry::default();
        let active = registry.create_shell("/bin/sh", "/", 24, 80).id.clone();
        registry.start_shell(&active, &[]).unwrap();
        for _ in 0..64 {
            let id = registry.create_shell("/bin/true", "/", 24, 80).id.clone();
            registry.start_shell(&id, &[]).unwrap();
            assert_eq!(0, await_exit(&mut registry, &id));
            assert!(registry.get(&id).unwrap().pty.is_none());
        }
        assert_eq!(65, registry.list().count());
        assert_eq!(vec![active.clone()], registry.running().map(|session| session.id.clone()).collect::<Vec<_>>());
        registry.terminate(&active, Signal::SIGKILL).unwrap();
        assert_eq!(0, registry.running().count());
    }

    #[test]
    fn creating_session_metadata_does_not_require_a_ui_client() {
        let mut sessions = SessionRegistry::default();
        let id = sessions.create_shell("/bin/bash", "/home/user", 24, 80).id.clone();
        assert_eq!(sessions.get(&id).unwrap().state, SessionState::Created);
        assert_eq!(sessions.list().count(), 1);
    }

    #[test]
    fn terminating_middle_session_removes_only_its_entry() {
        let mut sessions = SessionRegistry::default();
        let ids = (0..3).map(|_| {
            let id = sessions.create_shell("/bin/sh", "/", 24, 80).id.clone();
            sessions.start_shell(&id, &[]).unwrap();
            id
        }).collect::<Vec<_>>();

        sessions.terminate(&ids[1], Signal::SIGKILL).unwrap();

        assert_eq!(sessions.list().map(|session| session.id.clone()).collect::<Vec<_>>(), vec![ids[0].clone(), ids[2].clone()]);
        assert!(matches!(sessions.terminate(&ids[1], Signal::SIGKILL), Err(PtyError::SessionNotFound)));
        sessions.terminate(&ids[0], Signal::SIGKILL).unwrap();
        sessions.terminate(&ids[2], Signal::SIGKILL).unwrap();
    }

    #[test]
    fn terminating_an_already_exited_session_removes_its_entry() {
        let mut sessions = SessionRegistry::default();
        let id = sessions.create_shell("/bin/true", "/", 24, 80).id.clone();
        sessions.start_shell(&id, &[]).unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
        loop {
            if sessions.poll_exit(&id).unwrap().is_some() { break; }
            assert!(std::time::Instant::now() < deadline, "child did not exit before timeout");
            std::thread::sleep(std::time::Duration::from_millis(5));
        }

        sessions.terminate(&id, Signal::SIGKILL).unwrap();

        assert!(sessions.get(&id).is_none());
        assert_eq!(sessions.list().count(), 0);
    }
}
