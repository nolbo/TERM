//! Guest-to-host session response payloads. Mirrors `SessionResponseCodec` in
//! the Kotlin protocol module.

use crate::session::{Session, SessionState};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionStatus { Created, Running, Exited }

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionDescriptor {
    pub id: String,
    pub title: String,
    pub command: String,
    pub cwd: String,
    pub created_at_ms: i64,
    pub rows: u16,
    pub cols: u16,
    pub state: SessionStatus,
    pub exit_code: i32,
}

impl From<&Session> for SessionDescriptor {
    fn from(session: &Session) -> Self {
        let (state, exit_code) = match &session.state {
            SessionState::Created => (SessionStatus::Created, -1),
            SessionState::Running => (SessionStatus::Running, -1),
            SessionState::Exited(code) => (SessionStatus::Exited, *code),
        };
        Self {
            id: session.id.clone(), title: session.title.clone(), command: session.command.clone(),
            cwd: session.cwd.clone(), created_at_ms: session.created_at_ms.min(i64::MAX as u128) as i64,
            rows: session.rows, cols: session.cols, state, exit_code,
        }
    }
}

pub enum SessionResponse {
    Resources(crate::resources::GuestResources),
    Created(SessionDescriptor),
    Listed(Vec<SessionDescriptor>),
    Attached(SessionDescriptor),
    Detached(String),
    Resized(SessionDescriptor),
    Written { session_id: String, accepted_bytes: i32 },
    Terminating(String),
}

impl SessionResponse {
    pub fn encode(&self) -> Result<Vec<u8>, ResponseError> {
        let mut writer = Writer::default();
        match self {
            Self::Resources(usage) => {
                writer.byte(0x88);
                for value in [usage.total, usage.available, usage.swap_total, usage.swap_free] { writer.i64(value); }
                writer.i32(usage.cpus); writer.i32(usage.load_milli); writer.i32(usage.pressure_milli);
            }
            Self::Created(session) => { writer.byte(0x81); writer.descriptor(session)?; }
            Self::Listed(sessions) => {
                if sessions.len() > 1024 { return Err(ResponseError::InvalidLength); }
                writer.byte(0x82); writer.u16(sessions.len() as u16);
                for session in sessions { writer.descriptor(session)?; }
            }
            Self::Attached(session) => { writer.byte(0x83); writer.descriptor(session)?; }
            Self::Detached(id) => { writer.byte(0x84); writer.string(id)?; }
            Self::Resized(session) => { writer.byte(0x85); writer.descriptor(session)?; }
            Self::Written { session_id, accepted_bytes } => { writer.byte(0x86); writer.string(session_id)?; writer.i32(*accepted_bytes); }
            Self::Terminating(id) => { writer.byte(0x87); writer.string(id)?; }
        }
        Ok(writer.bytes)
    }
}

#[derive(Debug)]
pub enum ResponseError { InvalidLength, InvalidString }

#[derive(Default)]
struct Writer { bytes: Vec<u8> }

impl Writer {
    fn byte(&mut self, value: u8) { self.bytes.push(value); }
    fn u16(&mut self, value: u16) { self.bytes.extend_from_slice(&value.to_be_bytes()); }
    fn i32(&mut self, value: i32) { self.bytes.extend_from_slice(&value.to_be_bytes()); }
    fn i64(&mut self, value: i64) { self.bytes.extend_from_slice(&value.to_be_bytes()); }
    fn string(&mut self, value: &str) -> Result<(), ResponseError> {
        if value.len() > 4096 { return Err(ResponseError::InvalidString); }
        self.u16(value.len() as u16); self.bytes.extend_from_slice(value.as_bytes()); Ok(())
    }
    fn descriptor(&mut self, value: &SessionDescriptor) -> Result<(), ResponseError> {
        self.string(&value.id)?; self.string(&value.title)?; self.string(&value.command)?; self.string(&value.cwd)?;
        self.i64(value.created_at_ms); self.u16(value.rows); self.u16(value.cols);
        self.byte(match value.state { SessionStatus::Created => 0, SessionStatus::Running => 1, SessionStatus::Exited => 2 });
        self.i32(value.exit_code); Ok(())
    }
}
