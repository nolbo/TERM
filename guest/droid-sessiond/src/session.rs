//! Session metadata and PTYs are owned together by the guest daemon. UI client
//! attachment is only a registry operation; it never owns a shell process.

use std::collections::BTreeMap;
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
    next_id: u64,
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

    pub fn start_shell(&mut self, id: &str, environment: &[(String, String)]) -> Result<(), PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        if session.pty.is_none() {
            session.pty = Some(PtyProcess::spawn(
                &session.command,
                &session.cwd,
                environment,
                session.rows,
                session.cols,
            )?);
            session.state = SessionState::Running;
        }
        Ok(())
    }

    pub fn attach(&mut self, id: &str) -> Result<(), PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        if session.pty.is_none() { return Err(PtyError::SessionNotStarted); }
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
        {
            let session = self.sessions.get(id).ok_or(PtyError::SessionNotFound)?;
            session.pty.as_ref().ok_or(PtyError::SessionNotStarted)?.terminate(signal)?;
        }
        if let Some(mut removed) = self.sessions.remove(id) {
            if let Some(pty) = removed.pty.take() {
                std::thread::spawn(move || {
                    let _ = pty.wait_for_exit();
                });
            }
        }
        Ok(())
    }

    pub fn output_fd(&self, id: &str) -> Result<RawFd, PtyError> {
        let session = self.sessions.get(id).ok_or(PtyError::SessionNotFound)?;
        Ok(session.pty.as_ref().ok_or(PtyError::SessionNotStarted)?.raw_fd())
    }

    pub fn poll_exit(&mut self, id: &str) -> Result<Option<i32>, PtyError> {
        let session = self.sessions.get_mut(id).ok_or(PtyError::SessionNotFound)?;
        let code = session.pty.as_ref().ok_or(PtyError::SessionNotStarted)?.poll_exit()?;
        if let Some(exit_code) = code { session.state = SessionState::Exited(exit_code); }
        Ok(code)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creating_session_metadata_does_not_require_a_ui_client() {
        let mut sessions = SessionRegistry::default();
        let session = sessions.create_shell("/bin/bash", "/home/user", 24, 80);
        assert_eq!(sessions.get(&session.id).unwrap().state, SessionState::Created);
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
}
