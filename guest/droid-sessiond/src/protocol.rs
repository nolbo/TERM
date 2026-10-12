//! Protocol v1 framing shared with the host `protocol` module.

use std::convert::TryInto;

pub const VERSION: u16 = 1;
pub const HEADER_SIZE: usize = 24;
pub const MAX_PAYLOAD_BYTES: usize = 1_048_576;
const MAGIC: u32 = 0x4450_5459; // DPTY

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Service {
    Session = 1,
    Lifecycle = 2,
    Bridge = 3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FrameKind {
    Request = 1,
    Response = 2,
    Stream = 3,
    Cancel = 4,
    Error = 5,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub service: Service,
    pub kind: FrameKind,
    pub flags: u16,
    pub request_id: u64,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtocolError {
    ShortFrame,
    BadMagic,
    UnsupportedVersion(u16),
    UnknownService(u8),
    UnknownKind(u8),
    ReservedField,
    InvalidLength,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ErrorCode {
    Unsupported = 0,
    PermissionDenied = 1,
    CapabilityDenied = 2,
    NotFound = 3,
    InvalidArgument = 4,
    Timeout = 5,
    Disconnected = 6,
    GuestError = 7,
    HostError = 8,
}

pub fn encode_error(code: ErrorCode, message: &str) -> Result<Vec<u8>, ProtocolError> {
    if message.len() > 4096 { return Err(ProtocolError::InvalidLength); }
    let mut payload = Vec::with_capacity(3 + message.len());
    payload.push(code as u8);
    payload.extend_from_slice(&(message.len() as u16).to_be_bytes());
    payload.extend_from_slice(message.as_bytes());
    Ok(payload)
}

impl Frame {
    pub fn encode(&self) -> Result<Vec<u8>, ProtocolError> {
        if self.payload.len() > MAX_PAYLOAD_BYTES {
            return Err(ProtocolError::InvalidLength);
        }
        let mut bytes = Vec::with_capacity(HEADER_SIZE + self.payload.len());
        bytes.extend_from_slice(&MAGIC.to_be_bytes());
        bytes.extend_from_slice(&VERSION.to_be_bytes());
        bytes.push(self.service as u8);
        bytes.push(self.kind as u8);
        bytes.extend_from_slice(&self.flags.to_be_bytes());
        bytes.extend_from_slice(&0_u16.to_be_bytes());
        bytes.extend_from_slice(&self.request_id.to_be_bytes());
        bytes.extend_from_slice(&(self.payload.len() as u32).to_be_bytes());
        bytes.extend_from_slice(&self.payload);
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, ProtocolError> {
        if bytes.len() < HEADER_SIZE {
            return Err(ProtocolError::ShortFrame);
        }
        if u32::from_be_bytes(bytes[0..4].try_into().unwrap()) != MAGIC {
            return Err(ProtocolError::BadMagic);
        }
        let version = u16::from_be_bytes(bytes[4..6].try_into().unwrap());
        if version != VERSION {
            return Err(ProtocolError::UnsupportedVersion(version));
        }
        let service = match bytes[6] {
            1 => Service::Session,
            2 => Service::Lifecycle,
            3 => Service::Bridge,
            value => return Err(ProtocolError::UnknownService(value)),
        };
        let kind = match bytes[7] {
            1 => FrameKind::Request,
            2 => FrameKind::Response,
            3 => FrameKind::Stream,
            4 => FrameKind::Cancel,
            5 => FrameKind::Error,
            value => return Err(ProtocolError::UnknownKind(value)),
        };
        let flags = u16::from_be_bytes(bytes[8..10].try_into().unwrap());
        if u16::from_be_bytes(bytes[10..12].try_into().unwrap()) != 0 {
            return Err(ProtocolError::ReservedField);
        }
        let request_id = u64::from_be_bytes(bytes[12..20].try_into().unwrap());
        let payload_length = u32::from_be_bytes(bytes[20..24].try_into().unwrap()) as usize;
        if payload_length > MAX_PAYLOAD_BYTES || bytes.len() != HEADER_SIZE + payload_length {
            return Err(ProtocolError::InvalidLength);
        }
        Ok(Self {
            service,
            kind,
            flags,
            request_id,
            payload: bytes[HEADER_SIZE..].to_vec(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_a_session_request() {
        let frame = Frame {
            service: Service::Session,
            kind: FrameKind::Request,
            flags: 0,
            request_id: 42,
            payload: b"attach:alpha".to_vec(),
        };
        assert_eq!(Frame::decode(&frame.encode().unwrap()).unwrap(), frame);
    }
}
