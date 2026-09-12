# USB serial driver integration

Upstream: https://github.com/mik3y/usb-serial-for-android/tree/3.11.0 (MIT).
Archive SHA-256: `9fc12819db958e0e83bc3ec276600758e42e049ec528341c43954768a0793af4`.

`prepareUsbSerial` runs before runtime compilation, using `prepare.sh`. It verifies
the cached/downloaded archive, extracts driver classes and the three required
utility classes under `android-runtime/build/usb-serial`, and packages upstream
LICENSE.txt in `assets/licenses/usb-serial`. No upstream build system, JitPack
repository, extra native toolchain or Gradle version change is needed.

The generated sources receive two deliberate modifications:

- All nine `claimInterface(..., true)` sites become `false`; fail the build if the
  pinned site's count changes. A kernel-owned interface must fail, not detach.
- Remove generated BuildConfig imports and disable the CH34x debug baud-rate
  test hook. Production parameter validation is also enforced by the broker.

Driver reflection is retained by the runtime consumer ProGuard rules. Never edit
generated sources or commit archives/build output. For an upstream update, review
the new code, hash, claims, supported IDs and cancellation behavior; update tests
and this document explicitly. Unknown chips are not user-force-probed.

Reproduce preparation with `bash tools/usb-serial/prepare.sh android-runtime/build/usb-serial`.
The first run needs network access; later runs use the verified cache. The
canonical repository build compiles/packages the result.

Protocol and scope: [docs/USB.md](../../docs/USB.md). Tests validate policy,
buffering, uncertain writes, cancellation checks and client framing without
hardware; physical compatibility and sustained throughput require actual adapters.
