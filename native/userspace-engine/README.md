# Independent Userspace Linux engine

This workspace is the initial implementation of the
[engine design](../../docs/USERSPACE_ENGINE_DESIGN.md). It is not yet a replacement
for the application's accelerated PRoot backend. The full objective remains
better compatibility **and** better overall/interactive performance than that backend.

Implemented components:

- `ue-abi`: raw AArch64 syscall/return types and revision.
- `ue-core`: bounded ELF inspection, explicit credential queries and checked branch encoding.
- `ue-platform`: freestanding host syscall gate, kernel signal layouts, probe BPF and code synchronization.
- `ue-runtime`: an isolated, single-thread entry experiment sharing one dispatcher
  between a known AAPCS wrapper connection and SIGSYS.

The probe is deliberately closed-world. Its filter allows unlisted calls, its
dispatcher recognizes only the fixture syscall set, and its direct entry relies
on a known C-callable wrapper's valid stack and scratch-register contract. It
does not load arbitrary guest ELF files, virtualize paths, support guest signal
handlers or implement process lifecycle. Do not use it to launch a distribution.

## Build and verify

Use an ARM64 Linux host, Rust/Cargo 1.89+, Clang, LLD, Python 3 and Linux UAPI
headers. No Cargo packages or toolchains are downloaded by these commands.

```sh
tools/userspace-engine/test.sh
UE_PROBE_TARGET=aarch64-linux-android tools/userspace-engine/build-entry-probe.sh
```

The second command requires the already installed `aarch64-linux-android` Rust
target. The freestanding artifact uses no NDK libc. Both binaries are currently
fixed-address ET_EXEC probes, not a finalized bootstrap/artifact ABI.

For Android, package the probe separately from the terminal app. Supply the SDK
and an AAPT2 executable that actually runs on the host. Existing SDK/Gradle
configuration is not changed by this script.

```sh
UE_TEST_SDK_ROOT=/path/to/sdk UE_TEST_AAPT2=/path/to/working/aapt2 \
  tools/userspace-engine/build-probe-apk.sh /tmp/term-ue-probe.apk
```

The builder defaults to platform `android-37.0`, build-tools `36.0.0` and the
existing Android debug keystore. `UE_TEST_ANDROID_JAR`, `UE_TEST_BUILD_TOOLS` and
`UE_TEST_KEYSTORE` override these inputs. It compiles Java with `--release 17`;
the manifest retains minSdk 31 / targetSdk 35. Use the Android tooling to install
the APK and run:

```text
am instrument -w -r dev.terminal.ueprobe/dev.terminal.ueprobe.EntryProbe
```

This starts under the APK's app UID through Zygote. `adb shell` or `run-as` alone
does not establish inherited app-filter behavior. The runner requires both a
successful probe and initial seccomp mode 2; read `INSTRUMENTATION_CODE` and the
failure fields, not just an inner `RESULT` line. Enforcing SELinux and actual
16 KiB devices remain separate requirements even if this runner passes.

The timing fields are one diagnostic sample of 10,000 getpid calls, in fixed
order. They are not a benchmark verdict or evidence of PRoot superiority.
Current evidence, open work and design decisions are in
[implementation status](../../docs/USERSPACE_ENGINE_IMPLEMENTATION.md).
