//! Stage and verify before a single server-side rename. Never delete/truncate the live file.
use idevice::{IdeviceError, afc::{AfcClient, errors::AfcError, opcode::AfcFopenMode}};
use rootcause::prelude::*;

pub const TARGET: &str = "/Documents/ALTPairingFile.mobiledevicepairing";
#[async_trait::async_trait]
trait Files: Send {
    async fn write_new(&mut self, path: &str, data: &[u8]) -> Result<(), Report>;
    async fn read(&mut self, path: &str, limit: usize) -> Result<Vec<u8>, Report>;
    async fn rename(&mut self, from: &str, to: &str) -> Result<(), Report>;
}
#[async_trait::async_trait]
impl Files for AfcClient {
    async fn write_new(&mut self, path: &str, data: &[u8]) -> Result<(), Report> {
        // AFC has no O_EXCL: random host-only names plus fail-closed existence check.
        match self.get_file_info(path).await {
            Err(IdeviceError::Afc(AfcError::ObjectNotFound)) => (),
            Err(error) => return Err(error.into()),
            Ok(_) => rootcause::bail!("staging file exists"),
        }
        let mut file = self.open(path, AfcFopenMode::Wr).await?;
        let written = file.write_entire(data).await; let closed = file.close().await;
        written?; closed?; Ok(())
    }
    async fn read(&mut self, path: &str, limit: usize) -> Result<Vec<u8>, Report> {
        let mut file = self.open(path, AfcFopenMode::RdOnly).await?;
        let read = file.read_n(limit).await; let closed = file.close().await;
        let read = read?; closed?; Ok(read)
    }
    async fn rename(&mut self, from: &str, to: &str) -> Result<(), Report> {
        AfcClient::rename(self, from, to).await?; Ok(())
    }
}
async fn replace(files: &mut impl Files, bytes: &[u8], temporary: &str) -> Result<(), Report> {
    ensure!(!bytes.is_empty() && bytes.len() <= 256 * 1024, "pairing size invalid");
    files.write_new(temporary, bytes).await?;
    ensure!(files.read(temporary, bytes.len() + 1).await? == bytes, "staging verification failed");
    // If the service refuses replacement, fail; never remove the destination to retry.
    // If its reply is lost, retain uncertainty rather than undo a possibly accepted rename.
    files.rename(temporary, TARGET).await?;
    ensure!(files.read(TARGET, bytes.len() + 1).await? == bytes, "pairing verification failed");
    Ok(())
}
pub async fn place(afc: &mut AfcClient, bytes: &[u8]) -> Result<(), Report> {
    let temporary = format!("/Documents/.terminal-pairing-{}.tmp", uuid::Uuid::new_v4());
    // On failure/cancellation a random staging file may remain. No cleanup over a
    // potentially desynchronized AFC connection, and never touch unrelated files.
    replace(afc, bytes, &temporary).await
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fake { live: Vec<u8>, staged: Vec<u8>, step: usize, fail: usize, renamed: bool }
    impl Fake { fn advance(&mut self) -> Result<(), Report> { self.step += 1; ensure!(self.step != self.fail, "injected detach"); Ok(()) } }
    #[async_trait::async_trait]
    impl Files for Fake {
        async fn write_new(&mut self, path: &str, bytes: &[u8]) -> Result<(), Report> {
            assert_ne!(path, TARGET); self.staged = bytes[..1].to_vec();
            if self.fail == 6 { std::future::pending::<()>().await; }
            self.advance()?;
            self.staged = bytes.to_vec(); Ok(())
        }
        async fn read(&mut self, path: &str, _: usize) -> Result<Vec<u8>, Report> {
            if self.fail == 5 { self.step += 1; return Ok(b"corrupted".to_vec()); }
            self.advance()?; Ok(if path == TARGET { self.live.clone() } else { self.staged.clone() })
        }
        async fn rename(&mut self, _: &str, target: &str) -> Result<(), Report> {
            assert_eq!(target, TARGET); self.advance()?; self.live = self.staged.clone(); self.renamed = true;
            ensure!(self.fail != 7, "rename accepted but acknowledgment lost"); Ok(())
        }
    }
    #[tokio::test] async fn failure_never_truncates_live_pairing_and_never_retries_rename() {
        for fail in 0..=4 {
            let mut fs = Fake { live: b"old".to_vec(), staged: vec![], step: 0, fail, renamed: false };
            let result = replace(&mut fs, b"new-complete", "random-stage").await;
            assert_eq!(result.is_ok(), fail == 0);
            assert!(fs.live == if fail > 0 && fail <= 3 { b"old".to_vec() } else { b"new-complete".to_vec() });
            assert_eq!(fs.step, if fail == 0 { 4 } else { fail });
        }
    }
    #[tokio::test] async fn corrupt_staging_and_cancellation_preserve_old_file() {
        for fail in [5, 6, 7] {
            let mut fs = Fake { live: b"old".to_vec(), staged: vec![], step: 0, fail, renamed: false };
            let result = tokio::time::timeout(std::time::Duration::from_millis(20), replace(&mut fs, b"new", "stage")).await;
            if fail == 6 { assert!(result.is_err()); } else { assert!(result.unwrap().is_err()); }
            if fail == 7 { assert_eq!(fs.live, b"new"); assert_eq!(fs.step, 3); }
            else { assert_eq!(fs.live, b"old"); assert!(!fs.renamed); }
        }
    }
}
