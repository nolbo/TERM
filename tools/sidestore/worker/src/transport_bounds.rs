//! Exercise patched upstream parsers, not a broker-shaped substitute.
use idevice::{Idevice, IdeviceError, afc::{MAGIC, packet::AfcPacket},
    lockdown::LockdownClient, usbmuxd::UsbmuxdConnection};
use tokio::io::AsyncWriteExt;

fn bounded_error(result: Result<impl Sized, IdeviceError>) {
    assert!(matches!(result, Err(IdeviceError::UnexpectedResponse(_))));
}

#[tokio::test]
async fn afc_read_enforces_requested_count_and_closes_cleanly() {
    use idevice::afc::{AfcClient, opcode::AfcFopenMode};
    for payload in [b"x".as_slice(), b"too-long".as_slice()] {
        let (stream, mut peer) = tokio::io::duplex(4096);
        // FileOpenRes(fd=1), Data, Status(Success): no real files involved.
        let mut replies = Vec::new();
        for (op, header, body) in [(14u64, 1u64.to_le_bytes().to_vec(), vec![]),
                                  (2, vec![], payload.to_vec()),
                                  (1, 0u64.to_le_bytes().to_vec(), vec![])] {
            let size = 40 + header.len() as u64;
            replies.extend([MAGIC, size + body.len() as u64, size, 0, op].into_iter().flat_map(u64::to_le_bytes));
            replies.extend(header); replies.extend(body);
        }
        peer.write_all(&replies).await.unwrap();
        let mut afc = AfcClient::new(Idevice::new(Box::new(stream), "test"));
        let mut file = afc.open("/test", AfcFopenMode::RdOnly).await.unwrap();
        let read = file.read_n(1).await;
        file.close().await.unwrap();
        if payload.len() == 1 { assert_eq!(read.unwrap(), payload); }
        else { bounded_error(read); }
    }
}

#[tokio::test]
async fn malformed_afc_lengths_return_errors_without_allocation_or_panic() {
    for (entire, header) in [(39, 39), (40, 41), (u64::MAX, 40), (16 * 1024 * 1024 + 1, 40)] {
        let (stream, mut peer) = tokio::io::duplex(4096);
        let bytes: Vec<u8> = [MAGIC, entire, header, 0, 1].into_iter().flat_map(u64::to_le_bytes).collect();
        peer.write_all(&bytes).await.unwrap();
        let mut device = Idevice::new(Box::new(stream), "test");
        bounded_error(tokio::time::timeout(std::time::Duration::from_secs(1), AfcPacket::read(&mut device)).await.unwrap());
    }
}

#[tokio::test]
async fn mux_and_plist_lengths_are_checked_before_body_read() {
    for length in [0u32, 15, 256 * 1024 + 1, u32::MAX] {
        let (stream, mut peer) = tokio::io::duplex(4096);
        let bytes: Vec<u8> = [length, 1, 8, 0].into_iter().flat_map(u32::to_le_bytes).collect();
        peer.write_all(&bytes).await.unwrap();
        let mut mux = UsbmuxdConnection::new(Box::new(stream), 0);
        bounded_error(tokio::time::timeout(std::time::Duration::from_secs(1), mux.get_buid()).await.unwrap());
    }
    for length in [0u32, 32 * 1024 * 1024 + 1, u32::MAX] {
        let (stream, mut peer) = tokio::io::duplex(4096);
        peer.write_all(&length.to_be_bytes()).await.unwrap();
        let mut client = LockdownClient::new(Idevice::new(Box::new(stream), "test"));
        bounded_error(tokio::time::timeout(std::time::Duration::from_secs(1), client.get_value(None, None)).await.unwrap());
    }
}
