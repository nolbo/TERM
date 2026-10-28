//! `FrameKind::Stream` payloads for attached session output.

#[derive(Debug)]
pub enum SessionEvent<'a> {
    Output { session_id: &'a str, bytes: &'a [u8] },
    OutputGap { session_id: &'a str, dropped_bytes: i64 },
    Exited { session_id: &'a str, exit_code: i32 },
}

impl SessionEvent<'_> {
    pub fn encode(&self) -> Result<Vec<u8>, EventError> {
        let mut bytes = Vec::new();
        match self {
            Self::Output { session_id, bytes: output } => {
                if output.len() > 1_048_576 { return Err(EventError::InvalidLength); }
                bytes.push(0x90); write_string(&mut bytes, session_id)?;
                bytes.extend_from_slice(&(output.len() as i32).to_be_bytes()); bytes.extend_from_slice(output);
            }
            Self::OutputGap { session_id, dropped_bytes } => {
                bytes.push(0x92); write_string(&mut bytes, session_id)?; bytes.extend_from_slice(&dropped_bytes.to_be_bytes());
            }
            Self::Exited { session_id, exit_code } => {
                bytes.push(0x91); write_string(&mut bytes, session_id)?; bytes.extend_from_slice(&exit_code.to_be_bytes());
            }
        }
        Ok(bytes)
    }
}

#[derive(Debug)]
pub enum EventError { InvalidLength, InvalidString }

fn write_string(destination: &mut Vec<u8>, value: &str) -> Result<(), EventError> {
    if value.len() > 4096 { return Err(EventError::InvalidString); }
    destination.extend_from_slice(&(value.len() as u16).to_be_bytes());
    destination.extend_from_slice(value.as_bytes());
    Ok(())
}
