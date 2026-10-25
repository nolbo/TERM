//! Typed `Service::Session` payloads shared with the Kotlin protocol module.

const MAX_STRING_BYTES: usize = 4096;
const MAX_ENVIRONMENT_ENTRIES: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionRequest {
    Create {
        shell: String,
        cwd: String,
        env: Vec<(String, String)>,
        rows: u16,
        cols: u16,
    },
    List,
    ResourceUsage,
    Attach { session_id: String },
    Detach { session_id: String },
    Resize { session_id: String, rows: u16, cols: u16 },
    Write { session_id: String, bytes: Vec<u8> },
    Terminate { session_id: String, signal: i32 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionPayloadError {
    Truncated,
    InvalidRequest,
    InvalidUtf8,
    InvalidString,
    InvalidDimensions,
    DuplicateEnvironmentKey,
    TrailingBytes,
}

impl SessionRequest {
    pub fn encode(&self) -> Result<Vec<u8>, SessionPayloadError> {
        let mut writer = Writer::default();
        match self {
            Self::Create { shell, cwd, env, rows, cols } => {
                valid_dimensions(*rows, *cols)?;
                if env.len() > MAX_ENVIRONMENT_ENTRIES { return Err(SessionPayloadError::InvalidRequest); }
                writer.byte(1);
                writer.string(shell)?;
                writer.string(cwd)?;
                writer.u16(*rows);
                writer.u16(*cols);
                writer.u16(env.len() as u16);
                for (key, value) in env {
                    valid_environment_key(key)?;
                    writer.string(key)?;
                    writer.string(value)?;
                }
            }
            Self::List => writer.byte(2),
            Self::ResourceUsage => writer.byte(8),
            Self::Attach { session_id } => { writer.byte(3); writer.string(session_id)?; }
            Self::Detach { session_id } => { writer.byte(4); writer.string(session_id)?; }
            Self::Resize { session_id, rows, cols } => {
                valid_dimensions(*rows, *cols)?;
                writer.byte(5); writer.string(session_id)?; writer.u16(*rows); writer.u16(*cols);
            }
            Self::Write { session_id, bytes } => {
                writer.byte(6); writer.string(session_id)?;
                writer.u32(bytes.len().try_into().map_err(|_| SessionPayloadError::InvalidRequest)?);
                writer.raw(bytes);
            }
            Self::Terminate { session_id, signal } => {
                writer.byte(7); writer.string(session_id)?; writer.i32(*signal);
            }
        }
        Ok(writer.bytes)
    }

    pub fn decode(payload: &[u8]) -> Result<Self, SessionPayloadError> {
        let mut reader = Reader { bytes: payload, offset: 0 };
        let request = match reader.byte()? {
            1 => {
                let shell = reader.string()?;
                let cwd = reader.string()?;
                let rows = reader.u16()?;
                let cols = reader.u16()?;
                valid_dimensions(rows, cols)?;
                let count = reader.u16()? as usize;
                if count > MAX_ENVIRONMENT_ENTRIES { return Err(SessionPayloadError::InvalidRequest); }
                let mut env = Vec::with_capacity(count);
                for _ in 0..count {
                    let key = reader.string()?;
                    valid_environment_key(&key)?;
                    if env.iter().any(|(existing, _)| existing == &key) {
                        return Err(SessionPayloadError::DuplicateEnvironmentKey);
                    }
                    env.push((key, reader.string()?));
                }
                Self::Create { shell, cwd, env, rows, cols }
            }
            2 => Self::List,
            8 => Self::ResourceUsage,
            3 => Self::Attach { session_id: reader.string()? },
            4 => Self::Detach { session_id: reader.string()? },
            5 => {
                let session_id = reader.string()?;
                let rows = reader.u16()?;
                let cols = reader.u16()?;
                valid_dimensions(rows, cols)?;
                Self::Resize { session_id, rows, cols }
            }
            6 => {
                let session_id = reader.string()?;
                let length = reader.u32()? as usize;
                Self::Write { session_id, bytes: reader.raw(length)?.to_vec() }
            }
            7 => Self::Terminate { session_id: reader.string()?, signal: reader.i32()? },
            _ => return Err(SessionPayloadError::InvalidRequest),
        };
        if reader.offset != payload.len() { return Err(SessionPayloadError::TrailingBytes); }
        Ok(request)
    }
}

#[derive(Default)]
struct Writer { bytes: Vec<u8> }

impl Writer {
    fn byte(&mut self, value: u8) { self.bytes.push(value); }
    fn u16(&mut self, value: u16) { self.raw(&value.to_be_bytes()); }
    fn u32(&mut self, value: u32) { self.raw(&value.to_be_bytes()); }
    fn i32(&mut self, value: i32) { self.raw(&value.to_be_bytes()); }
    fn raw(&mut self, value: &[u8]) { self.bytes.extend_from_slice(value); }
    fn string(&mut self, value: &str) -> Result<(), SessionPayloadError> {
        if value.len() > MAX_STRING_BYTES { return Err(SessionPayloadError::InvalidString); }
        self.u16(value.len() as u16);
        self.raw(value.as_bytes());
        Ok(())
    }
}

struct Reader<'a> { bytes: &'a [u8], offset: usize }

impl<'a> Reader<'a> {
    fn raw(&mut self, size: usize) -> Result<&'a [u8], SessionPayloadError> {
        let end = self.offset.checked_add(size).ok_or(SessionPayloadError::Truncated)?;
        let value = self.bytes.get(self.offset..end).ok_or(SessionPayloadError::Truncated)?;
        self.offset = end;
        Ok(value)
    }
    fn byte(&mut self) -> Result<u8, SessionPayloadError> { Ok(self.raw(1)?[0]) }
    fn u16(&mut self) -> Result<u16, SessionPayloadError> {
        Ok(u16::from_be_bytes(self.raw(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32, SessionPayloadError> {
        Ok(u32::from_be_bytes(self.raw(4)?.try_into().unwrap()))
    }
    fn i32(&mut self) -> Result<i32, SessionPayloadError> {
        Ok(i32::from_be_bytes(self.raw(4)?.try_into().unwrap()))
    }
    fn string(&mut self) -> Result<String, SessionPayloadError> {
        let size = self.u16()? as usize;
        if size > MAX_STRING_BYTES { return Err(SessionPayloadError::InvalidString); }
        String::from_utf8(self.raw(size)?.to_vec()).map_err(|_| SessionPayloadError::InvalidUtf8)
    }
}

fn valid_dimensions(rows: u16, cols: u16) -> Result<(), SessionPayloadError> {
    if rows == 0 || cols == 0 { Err(SessionPayloadError::InvalidDimensions) } else { Ok(()) }
}

fn valid_environment_key(key: &str) -> Result<(), SessionPayloadError> {
    if key.is_empty() || key.contains('=') || key.contains('\0') {
        Err(SessionPayloadError::InvalidRequest)
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_binary_terminal_input() {
        let request = SessionRequest::Write { session_id: "session-1".into(), bytes: vec![0x1b, b'[', b'A', 0] };
        assert_eq!(SessionRequest::decode(&request.encode().unwrap()).unwrap(), request);
    }
}
