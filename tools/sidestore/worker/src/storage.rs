use std::{fs::{self, File, OpenOptions}, io::{Read, Write}, os::unix::{fs::{MetadataExt, OpenOptionsExt, PermissionsExt}}, path::PathBuf};
use isideload::util::storage::SideloadingStorage;
use rootcause::prelude::*;
use sha2::{Digest, Sha256};

const LIMIT: u64 = 1024 * 1024;

#[derive(Clone)]
pub struct HostStorage(pub PathBuf);

impl HostStorage {
    pub fn new(path: PathBuf) -> Result<Self, Report> {
        let metadata = fs::symlink_metadata(&path)?;
        ensure!(metadata.is_dir() && metadata.uid() == unsafe { libc::getuid() } && metadata.mode() & 0o077 == 0, "invalid host store");
        Ok(Self(path.canonicalize()?))
    }
    fn path(&self, key: &str) -> PathBuf {
        self.0.join(format!("{:x}", Sha256::digest(key.as_bytes())))
    }
}

impl SideloadingStorage for HostStorage {
    fn store(&self, key: &str, value: &str) -> Result<(), Report> {
        ensure!(value.len() as u64 <= LIMIT, "state too large");
        let destination = self.path(key);
        let temporary = destination.with_extension(uuid::Uuid::new_v4().to_string());
        let result: Result<(), Report> = (|| {
            let mut file = OpenOptions::new().create_new(true).write(true).mode(0o600).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC).open(&temporary)?;
            file.write_all(value.as_bytes())?; file.sync_all()?;
            fs::rename(&temporary, &destination)?;
            File::open(&self.0)?.sync_all()?;
            Ok(())
        })();
        if result.is_err() { let _ = fs::remove_file(&temporary); }
        result
    }
    fn retrieve(&self, key: &str) -> Result<Option<String>, Report> {
        let path = self.path(key);
        let file = match OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC).open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error.into()),
        };
        let metadata = file.metadata()?;
        ensure!(metadata.is_file() && metadata.uid() == unsafe { libc::getuid() } && metadata.permissions().mode() & 0o077 == 0 && metadata.len() <= LIMIT, "invalid host state");
        let mut value = String::new(); file.take(LIMIT + 1).read_to_string(&mut value)?;
        ensure!(value.len() as u64 <= LIMIT, "state too large");
        Ok(if value.is_empty() { None } else { Some(value) })
    }
    fn delete(&self, key: &str) -> Result<(), Report> {
        match fs::remove_file(self.path(key)) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error.into()),
        }
    }
}
