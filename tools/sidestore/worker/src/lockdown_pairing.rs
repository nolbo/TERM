//! Host-only pairing acquisition. Only ENOENT authorizes creating a new record.
use idevice::{IdeviceError, IdeviceService, lockdown::LockdownClient,
    pairing_file::PairingFile, usbmuxd::RawPacket};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use crate::{connector, Failure, failure, phase};

pub fn device_failure(error: IdeviceError, fallback: &'static str) -> Failure {
    let code = match &error {
        IdeviceError::Socket(_) | IdeviceError::NoEstablishedConnection => "PAIRING_TRANSPORT",
        IdeviceError::Timeout => "PAIRING_TIMEOUT",
        IdeviceError::InvalidHostID => "PAIR_RECORD_REJECTED",
        IdeviceError::Plist(_) | IdeviceError::PemParseFailed(_) => "PAIR_RECORD_INVALID",
        _ => crate::device_code(&error, fallback),
    };
    let detail = match &error {
        IdeviceError::Socket(e) => match e.kind() {
            std::io::ErrorKind::UnexpectedEof => "IO_EOF",
            std::io::ErrorKind::ConnectionReset => "IO_RESET",
            std::io::ErrorKind::BrokenPipe => "IO_BROKEN_PIPE",
            std::io::ErrorKind::TimedOut => "IO_TIMEOUT",
            _ => "IO_OTHER",
        },
        IdeviceError::UnexpectedResponse(_) => "UNEXPECTED_RESPONSE",
        IdeviceError::UnknownErrorType(_) => "UNKNOWN_DEVICE_ERROR",
        IdeviceError::Plist(_) | IdeviceError::PemParseFailed(_) => "INVALID_RECORD",
        IdeviceError::InvalidHostID => "INVALID_HOST_ID",
        _ => "DEVICE_ERROR",
    };
    failure(code, error).with_detail(detail)
}

fn read_result(value: plist::Dictionary) -> Result<Option<PairingFile>, Failure> {
    if let Some(number) = value.get("Number") {
        return match number.as_unsigned_integer() {
            Some(2) if value.get("MessageType").and_then(|v| v.as_string()) == Some("Result")
                && !value.contains_key("PairRecordData") => Ok(None), // Android/POSIX ENOENT
            Some(13) => Err(failure("PAIR_RECORD_READ_FAILED", "permission").with_detail("STORE_PERMISSION")),
            Some(5) => Err(failure("PAIR_RECORD_READ_FAILED", "io").with_detail("STORE_IO")),
            _ => Err(failure("PAIR_RECORD_READ_FAILED", "unexpected status").with_detail("STORE_RESPONSE")),
        };
    }
    match value.get("PairRecordData").and_then(|v| v.as_data()) {
        Some(bytes) if !bytes.is_empty() && bytes.len() <= 128 * 1024 =>
            PairingFile::from_bytes(bytes).map(Some).map_err(|e| failure("PAIR_RECORD_INVALID", e).with_detail("INVALID_RECORD")),
        _ => Err(failure("PAIR_RECORD_INVALID", "missing or oversized record").with_detail("INVALID_RECORD")),
    }
}

async fn read_record(connector: &connector::Connector, udid: &str) -> Result<Option<PairingFile>, Failure> {
    // idevice 0.1.65's get_pair_record discards usbmux errno. Use its RawPacket
    // encoding with a bounded reader on the same anonymous connector, no listener/fork.
    let mut stream = connector.stream().await.map_err(|e| device_failure(e, "PAIR_RECORD_READ_FAILED"))?;
    let mut request = plist::Dictionary::new();
    request.insert("MessageType".into(), "ReadPairRecord".into());
    request.insert("PairRecordID".into(), udid.into());
    let request: Vec<u8> = RawPacket::new(request, 1, 8, 1).into();
    stream.write_all(&request).await.map_err(|e| device_failure(e.into(), "PAIR_RECORD_READ_FAILED"))?;
    read_response(&mut stream).await
}

async fn read_response(stream: &mut (impl tokio::io::AsyncRead + Unpin)) -> Result<Option<PairingFile>, Failure> {
    let mut header = [0u8; 16];
    stream.read_exact(&mut header).await.map_err(|e| device_failure(e.into(), "PAIR_RECORD_READ_FAILED"))?;
    let length = u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
    if !(16..=256 * 1024).contains(&length) || header[4..8] != 1u32.to_le_bytes()
        || header[8..12] != 8u32.to_le_bytes() || header[12..16] != 1u32.to_le_bytes() {
        return Err(failure("PAIR_RECORD_READ_FAILED", "invalid mux frame").with_detail("STORE_RESPONSE"));
    }
    let mut body = vec![0; length - 16];
    stream.read_exact(&mut body).await.map_err(|e| device_failure(e.into(), "PAIR_RECORD_READ_FAILED"))?;
    let result = plist::from_bytes(&body).map_err(|e| failure("PAIR_RECORD_INVALID", e).with_detail("INVALID_RECORD"));
    use zeroize::Zeroize;
    body.zeroize();
    read_result(result?)
}

pub async fn acquire(provider: &connector::PrivateProvider) -> Result<PairingFile, Failure> {
    phase("CHECKING_PAIR_RECORD");
    let saved = tokio::time::timeout(std::time::Duration::from_secs(15), read_record(&provider.connector, &provider.udid)).await
        .map_err(|_| failure("PAIRING_TIMEOUT", "record read timeout"))??;
    let record = match saved {
        Some(record) => record,
        None => {
            let mut mux = provider.connector.connection().await.map_err(|e| device_failure(e, "PAIRING_RESPONSE"))?;
            let buid = mux.get_buid().await.map_err(|e| device_failure(e, "PAIRING_RESPONSE"))?;
            let mut lockdown = LockdownClient::connect(provider).await.map_err(|e| device_failure(e, "PAIRING_RESPONSE"))?;
            phase("WAITING_FOR_TRUST");
            // Keep upstream's same-key pending-dialog retry. Do not blindly retry a
            // disconnected or rejected Pair request: the device may already have accepted it.
            let record = tokio::time::timeout(std::time::Duration::from_secs(120),
                lockdown.pair(uuid::Uuid::new_v4().to_string(), buid, Some("terminal"))).await
                .map_err(|_| failure("PAIRING_TIMEOUT", "trust timeout"))?
                .map_err(|e| device_failure(e, "PAIRING_RESPONSE"))?;
            phase("SAVING_PAIR_RECORD");
            let bytes = record.clone().serialize().map_err(|e| failure("PAIR_RECORD_INVALID", e).with_detail("INVALID_RECORD"))?;
            mux.save_pair_record(&provider.udid, bytes).await.map_err(|e| device_failure(e, "PAIR_RECORD_SAVE_FAILED"))?;
            // Confirm the host store can read the saved record before proceeding.
            read_record(&provider.connector, &provider.udid).await?
                .ok_or_else(|| failure("PAIR_RECORD_SAVE_FAILED", "saved record missing"))?
        }
    };
    phase("VALIDATING_PAIRING");
    let mut lockdown = LockdownClient::connect(provider).await.map_err(|e| device_failure(e, "PAIR_SESSION_FAILED"))?;
    lockdown.start_session(&record).await.map_err(|e| device_failure(e, "PAIR_SESSION_FAILED"))?;
    Ok(record)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn status(code: u64) -> plist::Dictionary {
        let mut value = plist::Dictionary::new(); value.insert("MessageType".into(), "Result".into());
        value.insert("Number".into(), code.into()); value
    }
    #[test] fn only_missing_records_allow_pairing() {
        assert!(matches!(read_result(status(2)), Ok(None)));
        for code in [0, 5, 13, 22] { assert!(read_result(status(code)).is_err()); }
        assert!(read_result(plist::Dictionary::new()).is_err());
        let mut invalid = plist::Dictionary::new(); invalid.insert("PairRecordData".into(), plist::Value::Data(b"broken".to_vec()));
        assert!(matches!(read_result(invalid), Err(Failure { code: "PAIR_RECORD_INVALID", .. })));
    }
    #[test] fn transport_and_response_diagnostics_do_not_copy_upstream_text() {
        let value = device_failure(IdeviceError::UnknownErrorType("credential-sentinel".into()), "PAIRING_RESPONSE");
        assert_eq!(value.detail, Some("UNKNOWN_DEVICE_ERROR"));
        assert!(!value.event().to_string().contains("credential-sentinel"));
        for (kind, detail) in [(std::io::ErrorKind::UnexpectedEof, "IO_EOF"), (std::io::ErrorKind::ConnectionReset, "IO_RESET")] {
            let value = device_failure(std::io::Error::from(kind).into(), "PAIRING_RESPONSE");
            assert_eq!(value.code, "PAIRING_TRANSPORT"); assert_eq!(value.detail, Some(detail));
        }
    }
    #[tokio::test] async fn bounded_mux_reader_preserves_errno_and_rejects_wrong_frames() {
        for code in [2, 5, 13] {
            let bytes: Vec<u8> = RawPacket::new(status(code), 1, 8, 1).into();
            let result = read_response(&mut bytes.as_slice()).await;
            if code == 2 { assert!(matches!(result, Ok(None))); }
            else { assert!(matches!(result, Err(Failure { code: "PAIR_RECORD_READ_FAILED", .. }))); }
        }
        for (length, version, message, tag) in [(15u32, 1u32, 8u32, 1u32), (300000, 1, 8, 1), (16, 0, 8, 1), (16, 1, 8, 7)] {
            let bytes: Vec<u8> = [length, version, message, tag].into_iter().flat_map(u32::to_le_bytes).collect();
            assert!(matches!(read_response(&mut bytes.as_slice()).await, Err(Failure { code: "PAIR_RECORD_READ_FAILED", .. })));
        }
        assert!(matches!(read_response(&mut b"short".as_slice()).await,
            Err(Failure { code: "PAIRING_TRANSPORT", detail: Some("IO_EOF"), .. })));
    }
}
