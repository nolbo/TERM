// Adapted from iLoader 348eefd7de78e9bc612c9d619b8b1e7a80ba3ba0 (MIT).
use idevice::{IdeviceError, IdeviceService, RemoteXpcClient, core_device_proxy::CoreDeviceProxy,
    house_arrest::HouseArrestClient, installation_proxy::InstallationProxyClient,
    lockdown::LockdownClient, provider::IdeviceProvider,
    remote_pairing::{RemotePairingClient, RpPairingFile}, rsd::RsdHandshake};
use isideload::util::storage::SideloadingStorage;
use rootcause::prelude::*;
use rootcause::option_ext::OptionExt;

pub fn needs_remote(version: &str) -> Result<bool, Report> {
    let parts = version.split('.').map(str::parse::<u32>).collect::<Result<Vec<_>, _>>()?;
    ensure!((1..=3).contains(&parts.len()), "invalid iOS version");
    Ok(parts[0] > 17 || (parts[0] == 17 && parts.get(1).copied().unwrap_or(0) >= 4))
}

async fn remote(provider: &dyn IdeviceProvider) -> Result<RpPairingFile, IdeviceError> {
    let proxy = CoreDeviceProxy::connect(provider).await?;
    let rsd_port = proxy.tunnel_info().server_rsd_port;
    let mut adapter = proxy.create_software_tunnel()?.to_async_handle();
    let handshake = RsdHandshake::new(adapter.connect(rsd_port).await?).await?;
    let port = handshake.services.get("com.apple.internal.dt.coredevice.untrusted.tunnelservice")
        .ok_or_else(|| IdeviceError::UnexpectedResponse("missing untrusted tunnel service".into()))?.port;
    let mut file = RpPairingFile::generate("terminal");
    // Use the new identity twice as iLoader does, ensuring device keychain commit.
    for _ in 0..2 {
        let mut xpc = RemoteXpcClient::new(adapter.connect(port).await?).await?;
        xpc.do_handshake().await?; let _ = xpc.recv_root().await;
        RemotePairingClient::new(xpc, "terminal").connect(&mut file, async || "000000".to_string()).await?;
    }
    Ok(file)
}

pub fn combine(lockdown: plist::Value, remote: Option<plist::Value>) -> Result<Vec<u8>, Report> {
    let mut dictionary = lockdown.into_dictionary().ok_or_report()?;
    if let Some(remote) = remote { dictionary.extend(remote.into_dictionary().ok_or_report()?); }
    let mut bytes = Vec::new(); plist::Value::Dictionary(dictionary).to_writer_xml(&mut bytes)?;
    ensure!(bytes.len() <= 256 * 1024, "pairing record too large");
    Ok(bytes)
}

pub(crate) async fn enable_wifi(lockdown: &mut LockdownClient) -> Result<&'static str, crate::Failure> {
    let previous = lockdown.get_value(Some("EnableWifiDebugging"), Some("com.apple.mobile.wireless_lockdown")).await
        .map_err(|e| crate::failure("WIFI_DEBUGGING", e))?;
    let enabled = previous.as_boolean().ok_or_else(|| crate::failure("WIFI_DEBUGGING", "unknown prior setting"))?;
    // Never overwrite an unreadable setting or disable a user's pre-existing setting.
    if !enabled {
        lockdown.set_value("EnableWifiDebugging", true.into(), Some("com.apple.mobile.wireless_lockdown")).await
            .map_err(|e| crate::failure("WIFI_DEBUGGING", e))?;
    }
    Ok(if enabled { "UNCHANGED" } else { "CONFIRMED" })
}

pub async fn generate(provider: &dyn IdeviceProvider, version: &str, udid: &str, storage: &dyn SideloadingStorage) -> Result<Vec<u8>, crate::Failure> {
    let mut record = provider.get_pairing_file().await.map_err(|e| crate::failure("PAIRING_FAILED", e))?;
    record.udid = Some(udid.into());
    let mut lockdown = LockdownClient::connect(provider).await.map_err(|e| crate::failure("PAIRING_FAILED", e))?;
    lockdown.start_session(&record).await.map_err(|e| crate::failure("PAIRING_FAILED", e))?;
    let wifi = enable_wifi(&mut lockdown).await?;
    crate::event(serde_json::json!({"phase":"GENERATING_PAIRING", "wifiDebugging": wifi}));
    let legacy = plist::Value::from_reader(std::io::Cursor::new(record.serialize().map_err(|e| crate::failure("PAIRING_FAILED", e))?))
        .map_err(|e| crate::failure("PAIRING_FAILED", e))?;
    if !needs_remote(version).map_err(|e| crate::failure("UNSUPPORTED_IOS_PAIRING", e))? {
        return combine(legacy, None).map_err(|e| crate::failure("PAIRING_FAILED", e));
    }
    let key = format!("rppairing_file_{udid}");
    let cached = storage.retrieve_data(&key).map_err(|e| crate::failure("RP_PAIRING", e))?;
    let data = if let Some(bytes) = cached.filter(|bytes| RpPairingFile::from_bytes(bytes).is_ok()) { bytes }
        else {
            let bytes = remote(provider).await.map_err(|e| crate::failure("RP_PAIRING", e))?.to_bytes();
            storage.store_data(&key, &bytes).map_err(|e| crate::failure("RP_PAIRING", e))?;
            bytes
        };
    let remote = plist::Value::from_reader(std::io::Cursor::new(data)).map_err(|e| crate::failure("RP_PAIRING", e))?;
    combine(legacy, Some(remote)).map_err(|e| crate::failure("RP_PAIRING", e))
}

pub async fn place(provider: &dyn IdeviceProvider, signed_id: &str, bytes: &[u8]) -> Result<(), Report> {
    let mut proxy = InstallationProxyClient::connect(provider).await?;
    let apps = proxy.browse(None).await?;
    let matches: Vec<_> = apps.iter().filter_map(|app| app.as_dictionary()?.get("CFBundleIdentifier")?.as_string())
        .filter(|id| *id == signed_id).collect();
    ensure!(matches.len() == 1, "installed SideStore identity not found");
    let mut afc = HouseArrestClient::connect(provider).await?.vend_documents(matches[0].to_owned()).await?;
    crate::pairing_placement::place(&mut afc, bytes).await
}
