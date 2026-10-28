# droid-sessiond

The guest-owned session daemon owns Linux PTYs independently of Android UI
clients. It serves the versioned session protocol on host-CID-only vsock 40500
and a root-authenticated Unix endpoint used by the recovery relay. Detaching or
losing a client removes subscriptions without terminating PTYs. PTY EOF and
child exit are separate: closed output never disconnects sibling sessions, and
the live process remains listable and terminable until it is reaped.

`droid-sessiond --session-recovery` runs the native recovery service on vsock
40507. It retains one token-scoped logical connection to sessiond, with bounded
256 KiB duplex TRS2 byte replay, consumption credit, heartbeats and a 60-second
raw-link recovery lease. It never re-executes session RPCs. Optional TSR1 release
closes only the matching logical owner, leaving PTY ownership in sessiond.
The image installs this mode as `term-session-recovery.service`. Older Python
relay helpers remain for compatibility and the opt-in comparison fixture.

From the repository root:

```sh
cargo test --manifest-path guest/droid-sessiond/Cargo.toml --offline
cargo run --manifest-path guest/droid-sessiond/Cargo.toml --offline -- --self-check
cargo test --release --manifest-path guest/droid-sessiond/Cargo.toml --offline native_and_python_transport_benchmark -- --ignored --nocapture
```

The benchmark needs Python 3 and local Unix sockets. It compares relay overhead;
it does not measure real AVF vsock or end-to-end interactive performance.
See `docs/PROTOCOL.md`, `docs/AVF_NOTES.md` and `docs/AVF_UPDATES.md` for wire
contracts, design trade-offs and verified stopped-Environment deployment.
