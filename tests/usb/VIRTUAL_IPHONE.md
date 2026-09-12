# Virtual iPhone verification

This is a fault-injection test environment, not an iOS virtual machine. No real
Apple Account, iPhone identity, pairing record, certificate or IPA is needed.

| Layer | Real implementation under test | Simulated boundary |
| --- | --- | --- |
| Android Native | Packaged Bionic terminal-usb CLI; UsbBroker session authentication/revocation | Test-APK broker-shaped iPhone responses and file storage |
| Android Rootless | PRoot Debian and its actual Python terminal-usb zipapp; same authentication | Same VirtualIphone fixture and scenario matrix |
| Rust lockdown | Pinned idevice 0.1.65 Pair/GetValue framing and production error mapping | In-memory length-prefixed plist service |

The Android fixture forwards each request's version/token to the real UsbBroker
for authentication, but **does not route synthetic device operations through the
production device dispatcher or USB worker**. It does not test Android USB grants,
physical device reservations, USB transfers, lockdown TLS, RPPairing, House Arrest,
Apple signing, installation or SideStore refresh. Separate existing broker/worker
tests cover policy rejection and internal connector FDs. Hardware remains required
for end-to-end claims. No product DI hook or virtual-device permission bypass exists.

## Android scenario matrix

`VirtualIphoneDeviceTest` executes Native subprocesses and, when provisioned, real
Rootless sessions against the same synthetic iPhone:

- list/check cannot start pairing or installation;
- locked device, Trust denial, pairing failure, and synthetic pair/info success;
- fragmented frames, read detach, wrong offsets and oversized frame lengths;
- complete download, no overwrite, and cleanup of incomplete local downloads;
- disconnect after accepting an upload chunk but before acknowledging it;
- no implicit write retry or deletion of unrelated target files;
- waiting for Trust, duplicate start rejection, explicit cancellation;
- installation/placement failure status and nonzero watch exit;
- Native watch process termination does not cancel the synthetic host task;
- real broker rejects a virtual device; forged/expired sessions cannot mutate it.

Tool phases and service failures in the Android fixture are scripted results, not
proof that actual iOS services or the production bootstrap state machine ran.
The Rust peer separately exercises actual upstream pending-Trust retries, verifies
the exact request/HostID/key material stays unchanged, then accepts, rejects, locks
or disconnects. A silent-peer deadline test closes without extra cleanup commands.
Only a synthetic public RSA key is committed. Generated host secrets stay in memory
and are never printed or persisted by the tests.

## Run

```sh
./gradlew :android-app:assembleDebug :android-app:assembleDebugAndroidTest --console=plain
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb install -r android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.terminal.app.VirtualIphoneDeviceTest \
  dev.terminal.app.test/androidx.test.runner.AndroidJUnitRunner
```

Select the intended test Android device when multiple devices are attached.
Rootless requires provisioned Debian with `python3` installed using that environment's
package manager. Missing Debian or Python produces an explicit assumption/skip,
**not a pass**. Tests never install dependencies, contact Apple, or substitute host
Python for Rootless. They create only uniquely named test workspace/cache directories
and remove those directories after the test. They do not terminate existing sessions.

For the Rust peer, use the repository-selected NDK and existing Cargo configuration:

```sh
bash tools/sidestore/build.sh /tmp/sidestore-worker-tests "$TERMINAL_ANDROID_NDK" --test
```

No new dependency/version or moving upstream reference is introduced. Android
fixtures live exclusively under `src/androidTest`; the Rust peer is `cfg(test)`.
There is no simulator command in the product CLI, public usbmux endpoint, executable
download to Android, or Rootless access to USB FDs/pair records.
