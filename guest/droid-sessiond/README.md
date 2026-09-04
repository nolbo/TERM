# droid-sessiond

This is the guest-owned terminal session daemon. It will own PTYs and retain
them while Android UI clients attach and detach. The initial spike deliberately
contains the versioned frame codec, typed session command codec, and a Linux
PTY owner. `SessionRegistry` exclusively owns the PTY master descriptor, while
UI clients only attach or detach. The daemon binds a framed, host-CID-only
AF_VSOCK listener on port `40500` and dispatches typed session requests. A
session is `Created` until the daemon has successfully spawned its shell, then
becomes `Running`.

On a Rust-enabled development host, run:

```bash
cargo test
cargo run -- --self-check
```

An attached client receives binary `STREAM` output frames. Detaching or closing
the client connection removes its subscription and does not terminate the PTY.
The next implementation step is the Android host-side vsock client and UI
reconnection path. No Activity, WebView, or ttyd will own a shell process.
