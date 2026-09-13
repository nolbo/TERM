# Pinned isideload host-tool recipe

This trusted APK tool is registered for the shared Native/Rootless CLI
environment, not a product install screen. `tooling start isideload DEVICE`
requests the fixed SideStore stable workflow; arbitrary IPA/argv/URL input is
not a capability. The generic host controller owns the operation and secure
login/2FA input. Packaged executable name: isideload (libisideload.so).

Pins:

- isideload **0.3.17**, **b6d111376657a59207ac26c8ef8be5cca8793cba**;
  immutable archive/digest in sources.lock.
- idevice **0.1.65** SHA-256-pinned crate with local parser bounds patch;
  reusable tools/iphone/connector provider. Original archive and patched source
  are included in the corresponding source bundle.
- iLoader pairing reference **348eefd7de78e9bc612c9d619b8b1e7a80ba3ba0**.
- Official SideStore stable **0.6.4**, exact 27,609,918-byte IPA, bundle
  `com.SideStore.SideStore`, SHA-256
  `316d0dbe75ac6a5f05dc2946754cf7d5b634eeec5370f02cf1a91f9a0ed913b9`.
- Rust **1.89.0**, all crates/checksums in worker/Cargo.lock (--locked).

isideload handles Apple login/trusted-device 2FA, developer sessions/team choice,
registration, development certificates and explicit limit/revocation decisions,
App IDs/profiles, bundle rewriting, ALTCertificate.p12/ID injection and signing.
idevice handles installation proxy, Wi-Fi debugging, CoreDevice/RSD software
tunnel/RPPairing (17.4+), cached combined pairing and House Arrest/AFC placement.
Placement verifies actual signed identity and bounded-rereads the written file.
These implemented source paths are NOT proof of successful physical installation.

The small upstream patch loads Android OS PEM CAs instead of JavaVM-dependent
platform verification and disables proxy discovery, without disabling TLS.
HTTPS redirects/download size/time/hash and XML/binary IPA identity are checked;
traversal/symlink archives and excessive expansion are rejected.

Private stdin credential commands are NOT a guest API. Generic host-owned
secure login/2FA input and explicit team/certificate decisions are connected.
Never collect an Apple account/password/2FA in Rootless PTY,
transcript, argv/env or diagnostic JSON. Host input defaults to official
`https://ani.sidestore.io` ([endorsed list](https://servers.sidestore.io/servers.json));
require a valid HTTPS host without embedded credentials/query/fragment.
Remote V3 sends provisioning data, not Apple passwords, as explained by
[SideStore](https://docs.sidestore.io/docs/advanced/anisette).

HostStorage uses hashed bounded keys, atomic 0600 NOFOLLOW/CLOEXEC files, fsync
and an owner-only 0700 no-backup parent supplied by the host. It has current
pairing-storage protection, not Keystore encryption. Do not bind it to Rootless.
Passwords/2FA are not persisted. Hosts must delete operation artifacts on all
termination paths, after worker reaping; orphan operation directories are
removed at the next service creation without resuming privileged work.

```sh
bash tools/sidestore/build.sh android-runtime/build/sidestore-native /ABSOLUTE/NDK
bash tools/sidestore/build.sh /tmp/sidestore-worker-tests /ABSOLUTE/NDK --test
./gradlew :android-runtime:buildSideStoreBootstrap --console=plain
```

Generated binaries stay out of Git. NOTICE.txt documents MIT worker/isideload/
idevice, MPL crates, separate GPL USB owner and SideStore AGPL IPA notices. Ship
generated complete source bundles as required, not just source URLs. Source
bundle includes immutable upstream archive, local patch/provider/worker and
locked vendored crate sources. For offline rebuild extract at a workspace root,
copy vendor-config.toml to .cargo/config.toml, and place bundled sources in the
chosen build directory's sources/ before running build.sh with pinned Rust/NDK.

Remaining physical validation: real Android USB Host/iPhone login/sign/
install/placement, disconnect during those stages, and first refresh after
Developer Mode/profile trust/LocalDevVPN. Free Personal Team usually expires in
seven days and Apple limits/server changes apply. Arbitrary IPA/LiveContainer/
TrollStore, public usbmux and iOS security bypasses are not implemented here.
