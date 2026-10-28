//! Encode session stream events directly into one complete wire frame.

use crate::protocol::{encode_header, FrameKind, Service};

#[derive(Debug)]
pub enum SessionEvent<'a> {
    Output { session_id: &'a str, bytes: &'a [u8] },
    OutputGap { session_id: &'a str, dropped_bytes: i64 },
    Exited { session_id: &'a str, exit_code: i32 },
}

impl SessionEvent<'_> {
    pub fn encode_frame(&self) -> Result<Vec<u8>, EventError> {
        let (id, data_length) = match self {
            Self::Output { session_id, bytes } => (*session_id, 4usize.checked_add(bytes.len()).ok_or(EventError::InvalidLength)?),
            Self::OutputGap { session_id, .. } => (*session_id, 8),
            Self::Exited { session_id, .. } => (*session_id, 4),
        };
        if id.len() > 4096 { return Err(EventError::InvalidString); }
        let mut bytes = encode_header(Service::Session, FrameKind::Stream, 0, 0, 3 + id.len() + data_length)
            .map_err(|_| EventError::InvalidLength)?;
        match self {
            Self::Output { session_id, bytes: output } => {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{Frame, MAX_PAYLOAD_BYTES, HEADER_SIZE};

    #[test]
    fn direct_encoding_preserves_wire_contract_and_frame_limits() {
        let events = [
            (SessionEvent::Output { session_id: "s", bytes: b"\x00\xff" }, [vec![0x90, 0, 1, b's', 0, 0, 0, 2], vec![0, 255]].concat()),
            (SessionEvent::OutputGap { session_id: "s", dropped_bytes: -1 }, [vec![0x92, 0, 1, b's'], (-1i64).to_be_bytes().to_vec()].concat()),
            (SessionEvent::Exited { session_id: "s", exit_code: 7 }, vec![0x91, 0, 1, b's', 0, 0, 0, 7]),
        ];
        for (event, payload) in events {
            let expected = Frame { service: Service::Session, kind: FrameKind::Stream, flags: 0, request_id: 0, payload };
            assert_eq!(event.encode_frame().unwrap(), expected.encode().unwrap());
        }
        let output = vec![0; MAX_PAYLOAD_BYTES - 8];
        assert_eq!(SessionEvent::Output { session_id: "s", bytes: &output }.encode_frame().unwrap().len(), HEADER_SIZE + MAX_PAYLOAD_BYTES);
        assert!(SessionEvent::Output { session_id: "ss", bytes: &output }.encode_frame().is_err());
        assert!(SessionEvent::Exited { session_id: &"x".repeat(4097), exit_code: 0 }.encode_frame().is_err());
    }
}
