macro_rules! ensure {
    ($condition:expr, $message:literal) => { if !$condition { return Err(rootcause::report!($message)); } };
}
mod android_tls;
mod artifact;
use terminal_iphone_connector as connector;
mod pairing;
mod pairing_placement;
mod lockdown_pairing;
mod storage;
#[cfg(test)] mod tests;
#[cfg(test)] mod virtual_lockdown;
#[cfg(test)] mod transport_bounds;

use std::{io::{BufRead, BufReader, Read, Write}, os::fd::{AsRawFd, FromRawFd, OwnedFd}, path::PathBuf, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, Ordering}}};
use idevice::{IdeviceError, IdeviceService, lockdown::LockdownClient};
use isideload::{anisette::{AnisetteProvider, AnisetteData, AnisetteClientInfo, remote_v3::RemoteV3AnisetteProvider},
    auth::{apple_account::{AppleAccount, TwoFactorCallbackResponse}, grandslam::GrandSlam},
    dev::{developer_session::DeveloperSession, teams::TeamsApi, devices::DevicesApi},
    sideload::{SideloaderBuilder, builder::MaxCertsBehavior}};
use serde::Deserialize;
use serde_json::json;
use zeroize::{Zeroize, Zeroizing};

static INPUT: OnceLock<Mutex<BufReader<std::io::Stdin>>> = OnceLock::new();
static OUTPUT: Mutex<()> = Mutex::new(());

pub struct Failure { code: &'static str, _diagnostic: String, detail: Option<&'static str> }
impl Failure {
    fn with_detail(mut self, detail: &'static str) -> Self { self.detail = Some(detail); self }
    fn event(&self) -> serde_json::Value {
        let mut value = json!({"phase":"FAILED", "error":self.code});
        if let Some(detail) = self.detail { value["detail"] = detail.into(); }
        value
    }
}
pub fn failure(code: &'static str, diagnostic: impl std::fmt::Display) -> Failure {
    // Diagnostics remain in worker memory only. Never forward upstream strings.
    Failure { code, _diagnostic: diagnostic.to_string(), detail: None }
}
pub fn event(value: serde_json::Value) {
    let _lock = OUTPUT.lock().unwrap();
    let mut out = std::io::stdout().lock(); let _ = writeln!(out, "{value}"); let _ = out.flush();
}
fn phase(value: &str) { event(json!({"phase":value})); }

#[derive(Deserialize)]
#[serde(tag="op", deny_unknown_fields)]
enum Command {
    #[serde(rename="start")]
    Start { email: String, password: String, anisette: String },
    #[serde(rename="code")]
    Code { code: String },
    #[serde(rename="team")]
    Team { id: String },
    #[serde(rename="revoke")]
    Revoke { ids: Vec<String> },
}
fn read_line() -> Result<String, Failure> {
    let mut input = INPUT.get().unwrap().lock().map_err(|_| failure("CANCELED", "input lock"))?;
    let mut line = String::new();
    (&mut *input).take(16385).read_line(&mut line).map_err(|e| failure("CANCELED", e))?;
    if !line.ends_with('\n') || line.len() > 16384 { line.zeroize(); return Err(failure("CANCELED", "invalid input")); }
    Ok(line)
}
fn command() -> Result<Command, Failure> {
    let mut line = read_line()?;
    let value = serde_json::from_str(&line).map_err(|_| failure("CANCELED", "invalid command"));
    line.zeroize(); value
}

struct CheckedAnisette { remote: RemoteV3AnisetteProvider, failed: Arc<AtomicBool> }
#[async_trait::async_trait]
impl AnisetteProvider for CheckedAnisette {
    async fn get_anisette_data(&self) -> Result<AnisetteData, rootcause::Report> {
        let result = self.remote.get_anisette_data().await; if result.is_err() { self.failed.store(true, Ordering::Relaxed); } result
    }
    async fn get_client_info(&self) -> Result<AnisetteClientInfo, rootcause::Report> {
        let result = self.remote.get_client_info().await; if result.is_err() { self.failed.store(true, Ordering::Relaxed); } result
    }
    async fn provision(&mut self, gs: Arc<GrandSlam>) -> Result<(), rootcause::Report> {
        let result = self.remote.provision(gs).await; if result.is_err() { self.failed.store(true, Ordering::Relaxed); } result
    }
    fn needs_provisioning(&self) -> Result<bool, rootcause::Report> { self.remote.needs_provisioning() }
}

fn device_code(error: &IdeviceError, fallback: &'static str) -> &'static str {
    match error {
        IdeviceError::DeviceLocked | IdeviceError::PasswordProtected => "DEVICE_LOCKED",
        IdeviceError::UserDeniedPairing => "TRUST_DENIED",
        IdeviceError::ApplicationVerificationFailed(_) => "IOS_CERTIFICATE_REJECTED",
        _ => fallback,
    }
}
fn report_failure(report: rootcause::Report, fallback: &'static str) -> Failure {
    let mut code = fallback;
    for node in report.iter_reports() {
        if node.downcast_current_context::<reqwest::Error>().is_some() { code = "NETWORK"; }
        if let Some(error) = node.downcast_current_context::<IdeviceError>() { code = device_code(error, code); }
        if let Some(error) = node.downcast_current_context::<isideload::SideloadError>() {
            code = match error {
                isideload::SideloadError::DeveloperError(7460, _) => "CERTIFICATE_LIMIT",
                isideload::SideloadError::DeveloperError(9412 | 9401, _) => "APP_ID_LIMIT",
                isideload::SideloadError::AuthWithMessage(_, _) => "APPLE_CREDENTIALS",
                isideload::SideloadError::IdeviceError(error) => device_code(error, code),
                _ => code,
            };
        }
    }
    failure(code, report)
}

fn authenticate(address: &str, token: &str) -> Result<(OwnedFd, OwnedFd), Failure> {
    if address.is_empty() || address.len() > 100 || token.len() != 37 { return Err(failure("PAIRING_FAILED", "invalid handshake")); }
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
        if fd < 0 { return Err(failure("PAIRING_FAILED", std::io::Error::last_os_error())); }
        let owner = OwnedFd::from_raw_fd(fd);
        let mut sockaddr: libc::sockaddr_un = std::mem::zeroed(); sockaddr.sun_family = libc::AF_UNIX as _;
        for (index, byte) in address.bytes().enumerate() { sockaddr.sun_path[index + 1] = byte as _; }
        let length = std::mem::offset_of!(libc::sockaddr_un, sun_path) + address.len() + 1;
        if libc::connect(fd, &sockaddr as *const _ as _, length as _) != 0 { return Err(failure("PAIRING_FAILED", "host connect")); }
        let mut credentials: libc::ucred = std::mem::zeroed(); let mut size = std::mem::size_of_val(&credentials) as libc::socklen_t;
        if libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_PEERCRED, &mut credentials as *mut _ as _, &mut size) != 0 || credentials.uid != libc::getuid() {
            return Err(failure("PAIRING_FAILED", "host authentication"));
        }
        if libc::send(fd, token.as_ptr() as _, token.len(), libc::MSG_NOSIGNAL) != token.len() as isize {
            return Err(failure("PAIRING_FAILED", "host token write"));
        }
        let connector = connector::receive_fd(fd, b'M').map_err(|e| failure("PAIRING_FAILED", e))?;
        Ok((owner, connector))
    }
}

async fn bootstrap(connector: connector::Connector, store: storage::HostStorage, temp: PathBuf) -> Result<(), Failure> {
    phase("PAIRING");
    let mut mux = connector.connection().await.map_err(|e| failure("PAIRING_FAILED", e))?;
    let mut devices = Vec::new();
    for _ in 0..100 {
        devices = mux.get_devices().await.map_err(|e| failure("PAIRING_FAILED", e))?;
        if !devices.is_empty() { break; }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    if devices.len() != 1 { return Err(failure("UNSUPPORTED_USB_DEVICE", "expected exactly one physical device")); }
    let device = &devices[0];
    let provider = connector::PrivateProvider { connector, device_id: device.device_id, udid: device.udid.clone() };
    drop(mux);
    let pairing_record = tokio::time::timeout(std::time::Duration::from_secs(180), lockdown_pairing::acquire(&provider)).await
        .map_err(|_| failure("PAIRING_TIMEOUT", "pairing stage timeout"))??;
    phase("READING_DEVICE_INFORMATION");
    let mut lockdown = LockdownClient::connect(&provider).await.map_err(|e| failure(device_code(&e, "PAIRING_FAILED"), e))?;
    lockdown.start_session(&pairing_record).await.map_err(|e| lockdown_pairing::device_failure(e, "PAIR_SESSION_FAILED"))?;
    let mut values = Vec::new();
    for key in ["DeviceName", "ProductType", "ProductVersion", "DeviceClass"] {
        let value = lockdown.get_value(Some(key), None).await.map_err(|e| failure(device_code(&e, "PAIRING_FAILED"), e))?;
        let value = value.as_string().filter(|s| s.len() <= 256).ok_or_else(|| failure("PAIRING_FAILED", "invalid device information"))?.to_owned(); values.push(value);
    }
    if values[3] != "iPhone" { return Err(failure("UNSUPPORTED_USB_DEVICE", "device is not iPhone")); }
    let version = values[2].clone();
    event(json!({"phase":"WAITING_FOR_LOGIN", "device":{"name":values[0],"model":values[1],"ios":values[2]}}));
    let Command::Start { email, mut password, anisette } = command()? else { return Err(failure("CANCELED", "expected start")); };
    let mut password = Zeroizing::new(std::mem::take(&mut password));
    if email.len() > 320 || password.len() > 1024 { return Err(failure("APPLE_CREDENTIALS", "invalid account input")); }
    let url = reqwest::Url::parse(&anisette).map_err(|e| failure("ANISETTE", e))?;
    if url.scheme() != "https" || url.host_str().is_none() || !url.username().is_empty() || url.password().is_some() || url.query().is_some() || url.fragment().is_some() {
        return Err(failure("ANISETTE", "invalid endpoint"));
    }
    phase("APPLE_LOGIN");
    let anisette_failed = Arc::new(AtomicBool::new(false));
    let two_factor = Arc::new(AtomicBool::new(false));
    let remote = RemoteV3AnisetteProvider::new(url.as_str().trim_end_matches('/'), Box::new(store.clone()), "0".into()).map_err(|e| failure("ANISETTE", e))?;
    let login = AppleAccount::builder(&email).anisette_provider(CheckedAnisette { remote, failed: anisette_failed.clone() })
        .login(&password, |_params| async {
            two_factor.store(true, Ordering::Relaxed);
            phase("WAITING_FOR_2FA");
            let Command::Code { mut code } = command().map_err(|_| rootcause::report!("invalid 2FA input"))? else { return Err(rootcause::report!("expected 2FA input")); };
            if code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) { code.zeroize(); return Err(rootcause::report!("invalid 2FA code")); }
            phase("APPLE_LOGIN"); Ok(TwoFactorCallbackResponse::SubmitCode(code))
        }).await;
    password.zeroize();
    let mut account = login.map_err(|e| {
        if anisette_failed.load(Ordering::Relaxed) { return failure("ANISETTE", e); }
        let mut mapped = report_failure(e, if two_factor.load(Ordering::Relaxed) { "TWO_FACTOR" } else { "APPLE_CREDENTIALS" });
        if two_factor.load(Ordering::Relaxed) && mapped.code == "APPLE_CREDENTIALS" { mapped.code = "TWO_FACTOR"; }
        mapped
    })?;
    let mut developer = DeveloperSession::from_account(&mut account).await.map_err(|e| report_failure(e, "PROVISIONING"))?;
    let teams = developer.list_teams().await.map_err(|e| report_failure(e, "NO_DEVELOPER_TEAM"))?;
    if teams.is_empty() { return Err(failure("NO_DEVELOPER_TEAM", "no teams")); }
    let team = if teams.len() == 1 { teams[0].clone() } else {
        event(json!({"phase":"WAITING_FOR_TEAM_SELECTION","choices":teams.iter().map(|t| json!({"id":t.team_id,"label":t.name.as_deref().unwrap_or("Personal Team")})).collect::<Vec<_>>() }));
        let Command::Team { id } = command()? else { return Err(failure("NO_DEVELOPER_TEAM", "expected team choice")); };
        teams.into_iter().find(|t| t.team_id == id).ok_or_else(|| failure("NO_DEVELOPER_TEAM", "invalid team choice"))?
    };
    phase("REGISTERING_DEVICE");
    developer.ensure_device_registered(&team, &values[0], &device.udid, None).await.map_err(|e| report_failure(e, "PROVISIONING"))?;
    phase("DOWNLOADING_SIDESTORE");
    let ipa = temp.join("SideStore.ipa"); artifact::download(&ipa).await.map_err(|e| report_failure(e, "SIDESTORE_DOWNLOAD"))?;
    let mut sideloader = SideloaderBuilder::new(developer, email).machine_name("terminal Android".into()).storage(Box::new(store.clone()))
        .max_certs_behavior(MaxCertsBehavior::Prompt(Box::new(|certs| {
            event(json!({"phase":"WAITING_FOR_CERTIFICATE_REVOCATION","choices":certs.iter().filter_map(|c| Some(json!({"id":c.serial_number.as_ref()?,"label":c.machine_name.as_deref().or(c.name.as_deref()).unwrap_or("Development certificate")}))).collect::<Vec<_>>() }));
            let Command::Revoke { ids } = command().ok()? else { return None; };
            if ids.is_empty() || ids.len() > certs.len() || ids.iter().any(|id| !certs.iter().any(|c| c.serial_number.as_ref() == Some(id))) { return None; }
            phase("SIGNING"); Some(ids)
        }))).build();
    phase("SIGNING");
    let (signed, special) = sideloader.sign_app(ipa, Some(team), false, None::<fn(f32) -> std::future::Ready<()>>).await.map_err(|e| report_failure(e, "SIGNING"))?;
    if !matches!(special, Some(isideload::sideload::application::SpecialApp::SideStore)) { return Err(failure("SIGNING", "missing SideStore certificate injection")); }
    let signed_id = artifact::signed_identity(&signed).map_err(|e| failure("SIGNING", e))?;
    phase("INSTALLING");
    isideload::sideload::install::install_app(&provider, &signed, |progress| event(json!({"phase":"INSTALLING","progress":progress as f64 / 100.0}))).await.map_err(|e| report_failure(e, "INSTALLATION_PROXY"))?;
    phase("GENERATING_PAIRING");
    let pairing = pairing::generate(&provider, &version, &device.udid, &store).await?;
    phase("PLACING_PAIRING"); pairing::place(&provider, &signed_id, &pairing).await.map_err(|e| report_failure(e, "HOUSE_ARREST_AFC"))?;
    event(json!({"phase":"COMPLETED","bundleId":signed_id,"version":artifact::VERSION}));
    Ok(())
}

#[tokio::main(worker_threads = 2)]
async fn main() {
    unsafe { libc::umask(0o077); libc::prctl(libc::PR_SET_DUMPABLE, 0); libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0); }
    let _ = rustls::crypto::ring::default_provider().install_default();
    INPUT.set(Mutex::new(BufReader::new(std::io::stdin()))).ok();
    let args: Vec<_> = std::env::args().collect();
    if args.len() == 3 && args[1] == "--self-test" {
        use isideload::util::storage::SideloadingStorage;
        let passed = (|| -> Result<(), rootcause::Report> {
            let store = storage::HostStorage::new(PathBuf::from(&args[2]))?;
            store.store("self-test", "not-a-secret")?;
            ensure!(store.retrieve("self-test")?.as_deref() == Some("not-a-secret"), "storage self-test");
            store.delete("self-test")?;
            let _client = android_tls::builder().build()?;
            let mut dictionary = plist::Dictionary::new(); dictionary.insert("HostID".into(), "test".into());
            let remote = idevice::remote_pairing::RpPairingFile::generate("terminal-test").to_bytes();
            pairing::combine(dictionary.into(), Some(plist::Value::from_reader(std::io::Cursor::new(remote))?))?;
            ensure!(!pairing::needs_remote("17.3.1")? && pairing::needs_remote("17.4")?, "pairing self-test");
            Ok(())
        })().is_ok();
        event(json!({"selfTest":passed,"publicMuxSocket":false,"rawUsbFd":false}));
        if !passed { std::process::exit(1); } return;
    }
    if args.len() != 4 || args[1] != "bootstrap" { event(json!({"phase":"FAILED","error":"UNSUPPORTED_USB_DEVICE"})); return; }
    let store = match storage::HostStorage::new(PathBuf::from(&args[2])) { Ok(store) => store, Err(_) => { event(json!({"phase":"FAILED","error":"PROVISIONING"})); return; } };
    let token = match read_line() { Ok(token) => token, Err(_) => return };
    let (owner, connector) = match authenticate(&args[3], &token) { Ok(value) => value, Err(error) => { event(json!({"phase":"FAILED","error":error.code})); return; } };
    std::thread::spawn(move || { let mut byte = 0u8; unsafe { libc::recv(owner.as_raw_fd(), &mut byte as *mut _ as _, 1, 0); libc::_exit(1); } });
    let temp = std::env::var_os("TMPDIR").map(PathBuf::from).unwrap_or_default();
    if !temp.is_absolute() || temp == store.0 { event(json!({"phase":"FAILED","error":"PROVISIONING"})); return; }
    let connector = match connector::Connector::new(connector) {
        Ok(connector) => connector,
        Err(_) => { event(json!({"phase":"FAILED","error":"PAIRING_TRANSPORT"})); return; }
    };
    let result = tokio::time::timeout(std::time::Duration::from_secs(1800), bootstrap(connector, store, temp)).await;
    match result { Ok(Ok(())) => (), Ok(Err(error)) => event(error.event()), Err(_) => event(json!({"phase":"FAILED","error":"NETWORK"})) }
}
