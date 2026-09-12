# Android usbmuxd worker

The reusable Rust private connector and packaged Native CLI/probe are documented
in [connector/README.md](connector/README.md). The C `mux-owner` mode keeps the
physical Android USB owner alive while registered trusted tools request fresh
anonymous streams; it is never a guest usbmux listener. The existing GPL
corresponding-source bundle now includes these connector-owner changes.

The port compiles upstream usbmuxd protocol/async transport, not its Linux
daemon/main/udev/discovery entry point. `usb_android.c` wraps Android's granted
FD, selects the strict Apple mux interface and reuses upstream transfer logic.
`mux_client.c` and `mux_server.c` redirect the original client/server to internal
socketpairs. `pair_store.c` replaces Linux's system-wide store. `worker.c` exposes
pair, five-key info, unpair and bounded standard AFC list/get/put operations;
no public usbmuxd socket is created. `afc_operations.c` implements the scoped
file operations. `afc.patch` hardens the pinned upstream receiver's packet length,
allocation and truncation checks; the build applies it to a generated copy, leaving
the verified upstream source intact. Both are included in the source bundle.

Rebuild on the repository's Linux ARM64 host:

```sh
bash tools/iphone/build.sh android-runtime/build/iphone-native \
  "$ANDROID_NDK_ROOT"
```

`ANDROID_NDK_ROOT` must refer to the repository-selected NDK r28 installation.
Gradle supplies that path automatically. The output is a 16-KiB-aligned Android
API 31 ARM64 PIE, packaged as `libiphone-worker.so`, not a JNI library. It is
executed only through the central packaged executable loader. Source archives
are verified every run, and local native objects are incremental. No generated
ELF, source archive, private device data or local toolchain path is committed.

Pinned upstreams (URLs and exact hashes are in `sources.lock`):

- usbmuxd `3ded00c9985a5108cfc7591a309f9a23d57a8cba`, GPL v2 or v3 (choose v3).
- libusb 1.0.29, LGPL 2.1 or later.
- libplist 2.7.0, LGPL 2.1 or later.
- libimobiledevice-glue 1.3.2, LGPL 2.1 or later.
- libusbmuxd 2.1.1, LGPL 2.1 or later for the library.
- libimobiledevice 1.4.0, LGPL 2.1 or later for the library.
- Mbed TLS 3.6.7, Apache 2.0 (compatible with the GPLv3 combined executable).

Local port contributions are GPL-3.0-or-later; the combined worker uses GPLv3.
Upstream copyright/license texts are copied to the APK's `licenses/iphone`
assets. A distributor must supply the worker's complete corresponding source,
including pinned dependency sources, local port/build configuration and changes,
and comply with the applicable GPL/LGPL terms; an upstream URL or license notice
alone is not a corresponding-source delivery. Review obligations for the entire
distributed product before release. Do not describe this native worker as
permissively licensed or remove license/source materials from distribution.

Every Gradle build also produces
`android-runtime/build/distributions/iphone-worker-source.tar.gz`, containing
the pinned upstream archives and local port/build sources, without binaries or
device data. Ship this source material alongside the worker as appropriate for
the distribution method. Extract into a new directory, then run
`bash tools/iphone/build.sh . "$ANDROID_NDK_ROOT"` to rebuild without downloading
dependencies (the existing NDK/native compiler are still required).

`IphoneWorkerDeviceTest` runs the actual packaged ELF on Android, exercises mux
enumeration, ephemeral pairing key/certificate generation and pair-store protocol round trips without hardware, and rejects
arbitrary operations. It does not validate iOS trust, TLS or USB transfers.
Physical checks and compatibility exclusions are in `docs/USB.md`.
