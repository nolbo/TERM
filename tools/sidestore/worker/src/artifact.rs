use std::{fs::File, io::{Read, Write}, path::{Component, Path}};
use rootcause::prelude::*;
use rootcause::option_ext::OptionExt;
use sha2::{Digest, Sha256};

pub const BUNDLE_ID: &str = "com.SideStore.SideStore";
pub const VERSION: &str = "0.6.4";
pub const URL: &str = "https://github.com/SideStore/SideStore/releases/download/0.6.4/SideStore.ipa";
pub const SIZE: u64 = 27_609_918;
pub const SHA256: &str = "316d0dbe75ac6a5f05dc2946754cf7d5b634eeec5370f02cf1a91f9a0ed913b9";

pub fn signed_identity(bundle: &Path) -> Result<String, Report> {
    use std::os::unix::fs::OpenOptionsExt;
    let file = std::fs::OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC).open(bundle.join("Info.plist"))?;
    ensure!(file.metadata()?.is_file() && file.metadata()?.len() <= 65536, "invalid signed plist");
    let mut bytes = Vec::new(); file.take(65537).read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= 65536, "signed plist too large");
    let info = plist::Value::from_reader(std::io::Cursor::new(bytes))?;
    let id = info.as_dictionary().and_then(|d| d.get("CFBundleIdentifier")).and_then(|v| v.as_string()).ok_or_report()?;
    ensure!(id.starts_with(&format!("{BUNDLE_ID}.")) && id.len() <= 255 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_'), "invalid signed SideStore identity");
    Ok(id.to_owned())
}

pub async fn download(path: &Path) -> Result<(), Report> {
    let client = crate::android_tls::builder().timeout(std::time::Duration::from_secs(180))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            let url = attempt.url();
            if attempt.previous().len() < 5 && url.scheme() == "https" && matches!(url.host_str(), Some("github.com" | "release-assets.githubusercontent.com" | "objects.githubusercontent.com")) {
                attempt.follow()
            } else { attempt.stop() }
        })).build()?;
    let mut response = client.get(URL).send().await?.error_for_status()?;
    if let Some(size) = response.content_length() { ensure!(size == SIZE, "unexpected IPA size"); }
    let mut output = File::create(path)?;
    let mut size = 0u64; let mut digest = Sha256::new();
    while let Some(chunk) = response.chunk().await? {
        size += chunk.len() as u64;
        ensure!(size <= SIZE, "IPA download exceeds limit");
        digest.update(&chunk); output.write_all(&chunk)?;
        crate::event(serde_json::json!({"phase":"DOWNLOADING_SIDESTORE", "progress":size as f64 / SIZE as f64}));
    }
    ensure!(size == SIZE && format!("{:x}", digest.finalize()) == SHA256, "IPA digest mismatch");
    output.sync_all()?;
    validate(path)
}

pub fn validate(path: &Path) -> Result<(), Report> {
    let file = File::open(path)?;
    ensure!(file.metadata()?.len() <= 256 * 1024 * 1024, "IPA too large");
    let mut zip = zip::ZipArchive::new(file)?;
    ensure!(zip.len() <= 20_000, "too many IPA entries");
    let mut total = 0u64; let mut identities = Vec::new();
    for index in 0..zip.len() {
        let mut entry = zip.by_index(index)?;
        let enclosed = entry.enclosed_name().ok_or_report()?;
        ensure!(!enclosed.components().any(|part| matches!(part, Component::ParentDir | Component::RootDir)), "invalid IPA path");
        ensure!(entry.unix_mode().unwrap_or(0) & 0o170000 != 0o120000, "IPA symlink rejected");
        total = total.checked_add(entry.size()).ok_or_report()?;
        ensure!(total <= 512 * 1024 * 1024, "IPA expansion too large");
        let parts: Vec<_> = entry.name().split('/').collect();
        if parts.len() == 3 && parts[0] == "Payload" && parts[1].ends_with(".app") && parts[2] == "Info.plist" {
            ensure!(entry.size() <= 65536, "Info.plist too large");
            let mut data = Vec::new(); (&mut entry).take(65537).read_to_end(&mut data)?;
            ensure!(data.len() <= 65536, "Info.plist expansion too large");
            let info = plist::Value::from_reader(std::io::Cursor::new(data))?;
            identities.push(info.as_dictionary().and_then(|d| d.get("CFBundleIdentifier")).and_then(|v| v.as_string()).ok_or_report()?.to_owned());
        }
    }
    ensure!(identities == [BUNDLE_ID], "unexpected SideStore identity");
    Ok(())
}
