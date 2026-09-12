use super::*;
use std::{fs, os::unix::fs::PermissionsExt};
use isideload::util::storage::SideloadingStorage;

fn directory() -> PathBuf {
    let path = std::env::temp_dir().join(format!("sidestore-test-{}", uuid::Uuid::new_v4()));
    fs::create_dir(&path).unwrap(); fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap(); path
}

#[test]
fn pairing_version_boundary_and_combined_format() {
    for version in ["16.7", "17", "17.3.1"] { assert!(!pairing::needs_remote(version).unwrap()); }
    for version in ["17.4", "17.4.1", "18.0", "26.1"] { assert!(pairing::needs_remote(version).unwrap()); }
    for version in ["", "17.x", "-1", "17.-4", "17.4.1.9"] { assert!(pairing::needs_remote(version).is_err()); }
    let mut lockdown = plist::Dictionary::new(); lockdown.insert("HostID".into(), "host".into());
    let mut remote = plist::Dictionary::new(); remote.insert("RemoteUnlockHostKey".into(), "key".into());
    let bytes = pairing::combine(lockdown.clone().into(), Some(remote.into())).unwrap();
    let parsed = plist::Value::from_reader(std::io::Cursor::new(bytes)).unwrap();
    let dictionary = parsed.as_dictionary().unwrap();
    assert_eq!(dictionary["HostID"].as_string(), Some("host"));
    assert_eq!(dictionary["RemoteUnlockHostKey"].as_string(), Some("key"));
    let legacy = pairing::combine(lockdown.into(), None).unwrap();
    assert!(!plist::Value::from_reader(std::io::Cursor::new(legacy)).unwrap().as_dictionary().unwrap().contains_key("RemoteUnlockHostKey"));
    assert!(pairing::combine("not a dictionary".into(), None).is_err());
}

#[test]
fn host_storage_is_atomic_private_bounded_and_nofollow() {
    let path = directory(); let store = storage::HostStorage::new(path.clone()).unwrap();
    store.store("session", "secret").unwrap(); assert_eq!(store.retrieve("session").unwrap().as_deref(), Some("secret"));
    let files: Vec<_> = fs::read_dir(&path).unwrap().map(|e| e.unwrap().path()).collect(); assert_eq!(files.len(), 1);
    assert_eq!(fs::metadata(&files[0]).unwrap().permissions().mode() & 0o777, 0o600);
    assert!(store.store("huge", &"x".repeat(1024 * 1024 + 1)).is_err());
    assert_eq!(fs::read_dir(&path).unwrap().count(), 1);
    fs::remove_file(&files[0]).unwrap(); std::os::unix::fs::symlink("/dev/null", &files[0]).unwrap();
    assert!(store.retrieve("session").is_err()); store.delete("session").unwrap();
    assert_eq!(fs::read_dir(&path).unwrap().count(), 0);
    fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap(); assert!(storage::HostStorage::new(path.clone()).is_err());
    fs::remove_dir(&path).unwrap();
}

fn ipa(path: &std::path::Path, bundle: &str, binary: bool, entry: &str) {
    let mut zip = zip::ZipWriter::new(fs::File::create(path).unwrap());
    let mut dictionary = plist::Dictionary::new(); dictionary.insert("CFBundleIdentifier".into(), bundle.into());
    let mut bytes = Vec::new(); let value = plist::Value::Dictionary(dictionary);
    if binary { value.to_writer_binary(&mut bytes).unwrap(); } else { value.to_writer_xml(&mut bytes).unwrap(); }
    zip.start_file(entry, zip::write::SimpleFileOptions::default()).unwrap(); zip.write_all(&bytes).unwrap(); zip.finish().unwrap();
}

#[test]
fn sidestore_ipa_validates_actual_xml_and_binary_identity_not_substrings() {
    let root = directory(); let path = root.join("SideStore.ipa");
    for binary in [false, true] { ipa(&path, artifact::BUNDLE_ID, binary, "Payload/SideStore.app/Info.plist"); artifact::validate(&path).unwrap(); }
    ipa(&path, "other.com.SideStore.SideStore", false, "Payload/SideStore.app/Info.plist"); assert!(artifact::validate(&path).is_err());
    ipa(&path, artifact::BUNDLE_ID, false, "../Payload/SideStore.app/Info.plist"); assert!(artifact::validate(&path).is_err());
    fs::write(&path, b"not an IPA").unwrap(); assert!(artifact::validate(&path).is_err()); fs::remove_dir_all(root).unwrap();
}

#[test]
fn signed_bundle_identity_is_read_after_rewriting_and_rejects_symlinks() {
    let root = directory();
    let mut info = plist::Dictionary::new(); info.insert("CFBundleIdentifier".into(), "com.SideStore.SideStore.TEAM".into());
    plist::Value::Dictionary(info).to_file_xml(root.join("Info.plist")).unwrap();
    assert_eq!(artifact::signed_identity(&root).unwrap(), "com.SideStore.SideStore.TEAM");
    fs::rename(root.join("Info.plist"), root.join("real.plist")).unwrap();
    std::os::unix::fs::symlink(root.join("real.plist"), root.join("Info.plist")).unwrap();
    assert!(artifact::signed_identity(&root).is_err()); fs::remove_dir_all(root).unwrap();
}

#[test]
fn arbitrary_commands_and_extra_credentials_are_rejected() {
    assert!(serde_json::from_str::<Command>(r#"{"op":"ReadPairRecord"}"#).is_err());
    assert!(serde_json::from_str::<Command>(r#"{"op":"code","code":"123456","password":"secret"}"#).is_err());
    assert!(serde_json::from_str::<Command>(r#"{"op":"start","email":"a","password":"b","anisette":"https://example.com"}"#).is_ok());
    assert_eq!(device_code(&IdeviceError::DeviceLocked, "PAIRING_FAILED"), "DEVICE_LOCKED");
    assert_eq!(device_code(&IdeviceError::UserDeniedPairing, "PAIRING_FAILED"), "TRUST_DENIED");
    assert_eq!(device_code(&IdeviceError::ApplicationVerificationFailed("secret".into()), "INSTALLATION_PROXY"), "IOS_CERTIFICATE_REJECTED");
}

#[test]
fn connector_rejects_missing_descriptors_and_invalid_marker() {
    use std::os::unix::net::UnixStream;
    let (mut host, worker) = UnixStream::pair().unwrap(); host.write_all(b"X").unwrap();
    assert!(connector::receive_fd(worker.as_raw_fd(), b'M').is_err());
    host.write_all(b"M").unwrap(); assert!(connector::receive_fd(worker.as_raw_fd(), b'M').is_err());
}
