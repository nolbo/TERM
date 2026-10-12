use std::env;
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;

const SOCKET: &str = "/run/droid/droid-agent.sock";

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let request = match args.as_slice() {
        [group, action] if group == "clipboard" && action == "get" => b"clipboard get".to_vec(),
        [group, action, text] if group == "clipboard" && action == "set" => [b"clipboard set\0".as_slice(), text.as_bytes()].concat(),
        [command, url] if command == "open" => [b"open\0".as_slice(), url.as_bytes()].concat(),
        [command] if command == "battery" => b"battery".to_vec(),
        [command, flag1, title, flag2, text] if command == "notify" && flag1 == "--title" && flag2 == "--text" => [b"notify\0".as_slice(), title.as_bytes(), b"\0", text.as_bytes()].concat(),
        _ => { eprintln!("usage: droid clipboard get|set TEXT | droid open URL | droid notify --title TITLE --text TEXT"); std::process::exit(2); }
    };
    let mut stream = UnixStream::connect(SOCKET).unwrap_or_else(|error| { eprintln!("droid-agent unavailable: {error}"); std::process::exit(1) });
    stream.write_all(&request).unwrap_or_else(|error| { eprintln!("droid request failed: {error}"); std::process::exit(1) });
    stream.shutdown(std::net::Shutdown::Write).ok();
    let mut response = Vec::new();
    stream.read_to_end(&mut response).unwrap_or_else(|error| { eprintln!("droid response failed: {error}"); std::process::exit(1) });
    match response.split_first() {
        Some((&b'O', rest)) if rest.first() == Some(&0) => print!("{}", String::from_utf8_lossy(&rest[1..])),
        Some((&b'E', rest)) if rest.first() == Some(&0) => { eprintln!("{}", String::from_utf8_lossy(&rest[1..])); std::process::exit(1); }
        _ => { eprintln!("invalid droid-agent response"); std::process::exit(1); }
    }
}
