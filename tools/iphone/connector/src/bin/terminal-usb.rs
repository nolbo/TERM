//! Android/Bionic CLI for the same bounded broker used by the Rootless Python client.
use std::{fs::{self, OpenOptions}, io::{Read, Write}, os::{fd::FromRawFd, unix::{fs::{OpenOptionsExt, MetadataExt}, net::UnixStream}}, path::Path};
use serde_json::{Value, json};

const FRAME: usize = 48 * 1024;
const FILE_LIMIT: u64 = 64 * 1024 * 1024;
type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
struct Broker { stream: UnixStream, token: String }
impl Broker {
    fn new() -> Result<Self> {
        let address = std::env::var("TERMINAL_USB_SOCKET")?;
        let token = std::env::var("TERMINAL_USB_TOKEN")?;
        if address.is_empty() || address.len() > 100 || token.len() != 36 { return Err("Start a new Native or Rootless session in TERM".into()); }
        let stream = unsafe {
            let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0);
            if fd < 0 { return Err(std::io::Error::last_os_error().into()); }
            let stream = UnixStream::from_raw_fd(fd);
            let mut socket: libc::sockaddr_un = std::mem::zeroed(); socket.sun_family = libc::AF_UNIX as _;
            for (i, byte) in address.bytes().enumerate() { socket.sun_path[i + 1] = byte as _; }
            if libc::connect(fd, &socket as *const _ as _, (std::mem::offset_of!(libc::sockaddr_un, sun_path) + 1 + address.len()) as _) != 0 { return Err("USB broker unavailable".into()); }
            let mut credentials: libc::ucred = std::mem::zeroed(); let mut size = std::mem::size_of_val(&credentials) as libc::socklen_t;
            if libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_PEERCRED, &mut credentials as *mut _ as _, &mut size) != 0 || credentials.uid != libc::getuid() { return Err("USB broker UID mismatch".into()); }
            stream
        };
        stream.set_read_timeout(Some(std::time::Duration::from_secs(130)))?;
        stream.set_write_timeout(Some(std::time::Duration::from_secs(10)))?;
        Ok(Self { stream, token })
    }
    fn send(&mut self, mut request: Value) -> Result<()> {
        let object = request.as_object_mut().ok_or("Request must be an object")?;
        object.insert("version".into(), 1.into()); object.insert("token".into(), self.token.clone().into());
        let data = serde_json::to_vec(&request)?;
        if data.len() > FRAME { return Err("FRAME_TOO_LARGE".into()); }
        self.stream.write_all(&(data.len() as u32).to_be_bytes())?; self.stream.write_all(&data)?; Ok(())
    }
    fn receive(&mut self) -> Result<Value> {
        let mut length = [0u8; 4]; self.stream.read_exact(&mut length)?; let length = u32::from_be_bytes(length) as usize;
        if !(1..=FRAME).contains(&length) { return Err("INVALID_BROKER_FRAME".into()); }
        let mut data = vec![0; length]; self.stream.read_exact(&mut data)?;
        let value: Value = serde_json::from_slice(&data)?;
        if value["ok"] != true { return Err(value["error"].as_str().unwrap_or("USB_ERROR").to_owned().into()); }
        Ok(value["result"].clone())
    }
    fn call(&mut self, request: Value) -> Result<Value> { self.send(request)?; self.receive() }
}
struct Temporary(std::path::PathBuf);
impl Drop for Temporary { fn drop(&mut self) { let _ = fs::remove_file(&self.0); } }

fn download(broker: &mut Broker, device: &str, remote: &str, destination: &str) -> Result<Value> {
    let destination = std::path::absolute(destination)?;
    if fs::symlink_metadata(&destination).is_ok() { return Err("Destination already exists".into()); }
    broker.send(json!({"op":"iphone","action":"afc-get","device":device,"path":remote}))?;
    let start = broker.receive()?; let size = start["size"].as_u64().filter(|s| *s <= FILE_LIMIT).ok_or("Invalid download size")?;
    if start["event"] != "start" { return Err("Invalid download header".into()); }
    let path = destination.parent().ok_or("Missing parent")?.join(format!(".terminal-download-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_nanos()));
    let mut output = OpenOptions::new().write(true).create_new(true).mode(0o600).custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC).open(&path)?;
    let temporary = Temporary(path); // Only delete a file this operation successfully created.
    let mut offset = 0;
    loop {
        let result = broker.receive()?;
        if result["event"] != "data" {
            if result["completed"] != true || result["bytes"].as_u64() != Some(size) || offset != size { return Err("Incomplete download".into()); }
            break;
        }
        let text = result["data"].as_str().ok_or("Missing chunk")?;
        if result["offset"].as_u64() != Some(offset) || !(2..=32768).contains(&text.len()) || text.len() % 2 != 0 || !text.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) { return Err("Invalid download chunk".into()); }
        let bytes = (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i+2],16)).collect::<std::result::Result<Vec<_>,_>>()?;
        offset += bytes.len() as u64; if offset > size { return Err("Download exceeds declared size".into()); } output.write_all(&bytes)?;
    }
    output.sync_all()?; fs::hard_link(&temporary.0, &destination)?;
    Ok(json!({"completed":true,"bytes":size,"local":destination}))
}
fn upload(broker: &mut Broker, device: &str, source: &str, name: &str) -> Result<Value> {
    let mut file = OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC).open(source)?;
    let before = file.metadata()?; let size = before.len();
    if !before.is_file() || size > FILE_LIMIT { return Err("Upload requires a regular file of at most 64 MiB".into()); }
    broker.send(json!({"op":"iphone","action":"afc-put","device":device,"name":name,"size":size}))?;
    let ready = broker.receive()?;
    if ready["event"] != "ready" || ready["size"].as_u64() != Some(size) || !ready["path"].as_str().unwrap_or("").starts_with("/TerminalImports/") { return Err("Invalid upload header".into()); }
    let mut offset = 0;
    while offset < size {
        let mut bytes = vec![0u8; (size - offset).min(16384) as usize]; file.read_exact(&mut bytes)?;
        let text = bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
        broker.send(json!({"op":"iphone-data","offset":offset,"data":text}))?; offset += bytes.len() as u64;
        let result = broker.receive()?;
        if result["event"] != "accepted" || result["offset"].as_u64() != Some(offset) { return Err("Upload stream rejected".into()); }
    }
    let mut result = broker.receive()?; let after = file.metadata()?;
    if result["completed"] != true || result["bytes"].as_u64() != Some(size) || before.len() != after.len() || before.mtime() != after.mtime() || before.mtime_nsec() != after.mtime_nsec() { return Err("Incomplete upload or changed source; remote partial data may remain".into()); }
    result["path"] = ready["path"].clone(); Ok(result)
}
fn checked_probe(value: Value) -> Result<Value> {
    if value["tool"] != "idevice-check" || value["completed"] != true || value["privateConnector"] != true
        || value["publicMuxSocket"] != false || value.get("operation").is_some() || value.get("state").is_some()
        || value.get("requestAction").is_some_and(|action| action != "check") {
        return Err("TOOL_RESPONSE_MISMATCH: check received an unexpected host-tool response".into());
    }
    Ok(value)
}
fn run(args: &[String]) -> Result<Value> {
    let mut broker = Broker::new()?;
    match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["list"] => broker.call(json!({"op":"list"})),
        ["diagnostics"] => broker.call(json!({"op":"diagnostics"})),
        ["configuration",device,value] => {
            let configuration: u8 = value.parse()?;
            if configuration == 0 { return Err("Configuration must be 1..255".into()); }
            broker.call(json!({"op":"configuration","device":device,"configuration":configuration}))
        },
        ["tooling","list"] => broker.call(json!({"op":"tooling","action":"list"})),
        ["tooling","check",device] => checked_probe(broker.call(json!({"op":"tooling","action":"check","device":device}))?),
        ["iphone",action,device] if ["pair","info","unpair"].contains(action) => broker.call(json!({"op":"iphone","action":action,"device":device})),
        ["iphone","ls",device] => broker.call(json!({"op":"iphone","action":"afc-list","device":device,"path":"/"})),
        ["iphone","ls",device,path] => broker.call(json!({"op":"iphone","action":"afc-list","device":device,"path":path})),
        ["iphone","get",device,remote,local] => download(&mut broker, device, remote, local),
        ["iphone","put",device,local] => upload(&mut broker, device, local, Path::new(local).file_name().and_then(|s|s.to_str()).ok_or("Invalid filename")?),
        ["iphone","put",device,local,"--name",name] => upload(&mut broker, device, local, name),
        _ => Err("Use terminal-usb --help".into()),
    }
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.is_empty() || args == ["--help"] {
        println!("terminal-usb configuration DEVICE VALUE — separate device-wide approval; list again afterward. Never automatic.");
        println!("terminal-usb list | diagnostics | tooling list | tooling check DEVICE\nterminal-usb iphone pair|info|unpair DEVICE\nterminal-usb iphone ls DEVICE [AFC_PATH]\nterminal-usb iphone get DEVICE AFC_PATH LOCAL_FILE\nterminal-usb iphone put DEVICE LOCAL_FILE [--name ASCII_NAME]\nEach device action requires separate approval in the app USB screen. No raw USB FD, pair records or usbmux endpoint enter the terminal."); return;
    }
    match run(&args) { Ok(value) => { println!("{value}"); }, Err(error) => { eprintln!("{error}\nReview USB approvals in TERM. Do not automatically retry writes."); std::process::exit(1); } }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn check_rejects_non_probe_status_and_wrong_action() {
        assert!(checked_probe(json!({"tool":"other-tool","state":"FAILED"})).is_err());
        let mut valid = json!({"tool":"idevice-check","completed":true,"privateConnector":true,"publicMuxSocket":false});
        assert!(checked_probe(valid.clone()).is_ok());
        valid["requestAction"] = "list".into(); assert!(checked_probe(valid).is_err());
    }
}
