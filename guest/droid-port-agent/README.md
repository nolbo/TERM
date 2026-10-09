# droid-port-agent

Reports up to 128 local IPv4/IPv6 TCP listener endpoints over host-CID-only vsock 40502. Protocol-v1 legacy port events remain unchanged; address-aware events, initial snapshot boundaries and proxy-version advertisement are optional extensions. The host scopes reports to its selected Environment and separates observation from actual Android listener binding. Unreadable `/proc/net/tcp{,6}` disconnects reporting rather than inventing listener closures. Process/PID and HTTP protocol are not inferred.

New images also run a host-CID-only raw TCP proxy on vsock 40504. Its version/port/numeric-address header can target only currently observed local listeners; wildcard addresses map to same-family loopback. It accepts no arbitrary remote hosts, URI, shell command or Android capability. Header/connect/idle timeouts and a 64-connection budget bound resources. The Android owner binds only 127.0.0.1 and manages explicit host-port mappings separately.

Old agents remain discovery-only on new hosts. Old hosts ignore extension events and still receive legacy events; their prior same-numbered TCP/vsock assumption is not a functional TCP bridge. Existing images are never modified automatically. Build a compatible image to enable the new proxy.

Run `cargo test --manifest-path guest/droid-port-agent/Cargo.toml` for endpoint parsing, header validation and wire-encoding tests. AVF vsock integration still requires a compatible privileged device/image.
