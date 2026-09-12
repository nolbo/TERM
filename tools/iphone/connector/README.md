# Android-native iPhone tool substrate

`terminal-iphone-connector` supplies fresh private streams through
`idevice = 0.1.65`'s IdeviceProvider/UsbmuxdConnection APIs. No fork, USB discovery
or public usbmux endpoint is needed. Only a trusted APK process receives the
connector; Native and Rootless clients receive bounded capability results.

The default APK packages Android/Bionic `terminal-usb` and `iphone-tool-probe`.
Native exposes the former through a shell alias and `$TERMINAL_USB_CLIENT` for
non-interactive programs. Rootless uses the existing Python stdlib client.
See [the shared tooling plan](../../../docs/IPHONE_TOOLING.md).

## Build

Use official rustup, Rust **1.89.0**, the Android std target and repository NDK
**28.0.13004108**. Do not install mixed-architecture APT packages. Existing
ARM64 host Clang uses NDK headers/compiler-rt through ../android-clang.sh;
x86_64 hosts use NDK Clang. Rust/NDK are APK development tools, not runtime
dependencies. Standard CARGO_HOME/RUSTUP_HOME and optional TERMINAL_CARGO work.

```sh
rustup toolchain install 1.89.0 --profile minimal
rustup target add aarch64-linux-android --toolchain 1.89.0
bash tools/iphone/connector/build.sh android-runtime/build/iphone-tools /ABSOLUTE/NDK
bash tools/iphone/connector/build.sh /tmp/iphone-tools-tests /ABSOLUTE/NDK --test
```

Gradle buildIphoneTools is a preBuild dependency. Cargo.lock pins versions and
checksums; builds use --locked. APK ELF PIEs have 16 KiB alignment, RELRO/NOW and
NX stack. Never commit generated binaries/vendor or execute downloaded Native
code from writable Android app data.

## Licensing and validation

Local tools/provider and idevice are MIT. Complete vendored sources/notices,
lockfile and scripts are bundled in build/iphone-tools/distributions/
iphone-tools-source.tar.gz. Deliver it alongside the APK. The vendor config has
relative paths: after extraction copy vendor-config.toml to .cargo/config.toml
at the extracted workspace root to rebuild offline with the pinned Rust/NDK.
The separate GPLv3 C USB owner still requires its existing corresponding-source
bundle build/distributions/iphone-worker-source.tar.gz; no GPL objects link here.

Rust tests cover marker/count/CLOEXEC and handshake rejection. Android
IphoneToolingDeviceTest runs the actual packaged CLI/probe against synthetic
peers and proves fresh mux streams without USB. C self-test also requests fresh
connector FDs. These do not prove physical iPhone or SideStore compatibility.

Host IPC uses same UID, randomized abstract address and random stdin token.
Keep/watch the owner FD and fail closed on EOF. Never publish connector
addresses/tokens/FDs to either terminal or make the provider a general guest RPC.
Register bounded host tasks instead. The pinned tools/sidestore worker is now
packaged as isideload, behind the shared asynchronous host-tool operations and
generic main-process-only secure input. No arbitrary guest stdin relay exists.
