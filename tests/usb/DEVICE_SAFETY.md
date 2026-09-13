# USB device-safety verification / 기기 안전성 검증

This is a regression and acceptance process, not a guarantee against hardware,
cable, OS, firmware or Apple-service defects. Never describe virtual-test success
as physical-device safety certification. Applies to Native and Rootless equally.

## 1. Automated gate (no device access)

```sh
python3 tests/usb/safety_gate.py --scope python
python3 tests/usb/safety_gate.py --scope host --ndk "$TERMINAL_ANDROID_NDK"
```

Use the repository's existing JDK/Cargo/NDK setup. The host scope runs Python
client and gate tests, USB runtime JUnit tests, Rust connector tests and the pinned Rust worker
tests. Source downloads/builds may occur on the build host. It does not install
anything on Android, open USB, contact Apple, or issue restore/reset commands.
JSON contains commit, worktree dirty-state, scope, counts and statuses, not raw
logs, device IDs, credentials or pairing data. Exit 0 means only the selected
scope passed; failure is 1, missing evidence/skipped tests/timeout is 2. Physical
safety always remains `NOT_VALIDATED`; this runner cannot promote it to passed.
The smaller Python scope does not substitute for host/Android acceptance.
The Android verification CI runs the host gate and retains its JSON artifact;
hardware acceptance remains a separate release-review obligation, not a CI badge.
Only one safety runner may execute per checkout. Do not run separate Gradle tests
concurrently in that checkout: they share JUnit result files. The runner rejects
another safety runner instead of racing over those results.
Optional PyUSB/real apt-ADB suites are outside this gate; run them separately
when changing those paths, and report their skips explicitly.

| Hazard | Automated regression evidence | Remaining physical evidence |
| --- | --- | --- |
| Implicit device-wide USB change | `test_client.py` port guard; `IphonePolicyTest` normal-mode filtering | Active configuration before/after check, reconnect |
| Unauthorized access or secret disclosure | `TrustedIphoneToolPolicyTest`, `HostToolConversationTest`, worker/broker instrumentation | OS USB permission grant/denial/revocation |
| Unplug/cancel/retry causes extra writes | Python partial-send tests, Kotlin stage cancellation, Rust silent-peer and staged-file tests | Disconnect during each relevant stage |
| Existing pairing/app/settings damaged | Rust staging corruption/lost-ack tests, Wi-Fi read-before-write test; residual-change snapshots | Existing app/container and settings comparison |
| Unrelated Android session interrupted | `VirtualIphoneDeviceTest` watch detach, scoped worker tests | Live Native and Rootless sessions survive cancellation/navigation |

Static guards are tripwires, not proof of every call path. The virtual Android
fixture is not a real USB dispatcher; see [VIRTUAL_IPHONE.md](VIRTUAL_IPHONE.md).

## 2. Android integration gate

On a dedicated ARM64 test Android, build/install matching app and test APKs using
the commands in VIRTUAL_IPHONE.md, then run these instrumentation classes:

```sh
adb -s "$TERMINAL_TEST_SERIAL" shell am instrument -w -r \
  -e class dev.terminal.app.VirtualIphoneDeviceTest,dev.terminal.app.IphoneToolingDeviceTest,dev.terminal.app.IphoneWorkerDeviceTest,dev.terminal.app.UsbBrokerDeviceTest#realSocketRejectsWrongAndExpiredSessionsAndOversizedFrames \
  dev.terminal.app.test/androidx.test.runner.AndroidJUnitRunner
```

Require every selected test to finish successfully: assumption/skipped status
(`INSTRUMENTATION_STATUS_CODE: -3`), crash, timeout or missing final summary is
incomplete, not a pass. Provision Rootless Debian/Python beforehand; do not skip
it to claim Native/Rootless equivalence. Keep test APKs off production devices.

## 3. Physical acceptance — explicit, staged, supervised

Use backed-up test devices/account, a known-good data cable, sufficient battery
and free storage. Obtain separate consent for pairing, account changes, app
installation and enabling Wi-Fi debugging. Never run destructive tests on the
user's only device or automatically unplug, reboot, unpair, revoke certificates,
delete apps, switch recovery/DFU mode or reset settings as a safety check.

Record a private baseline: APK SHA-256/commit, Android model/build, iPhone
model/iOS build (beta separately), cable/power arrangement, normal unlocked state,
existing trust and Wi-Fi debugging setting, installed app/container status, and
one unrelated Native plus Rootless session with a harmless running workload.
Never collect passwords, tokens, private keys, pair plists or their contents.
Publish redacted categorical comparisons, not serial numbers or container data.

Run in order, stopping on the first unexpected change:

1. List/diagnostics/tooling check; deny then grant Android permission. Verify no
   installation/account changes and no implicit configuration switch. Check can
   lead to a separately approved pairing flow: do not assume it is read-only.
2. Trust: locked phone, rejection, approval; cancel while waiting. Confirm no
   automatic unpair/reset and fresh explicit authorization after reconnect.
3. On the test account only, run approved signing/install/pairing placement.
   Compare existing app data, unrelated files and Wi-Fi settings afterward.
4. Repeat with explicit cancellation and supervised cable removal during Trust,
   signing, installation and placement, one scenario at a time. Signing may
   already have changed Apple account state even with USB disconnected.
5. After each scenario verify both phones remain normally operable, charging/USB
   reconnect works, no unexpected reboot/thermal warning, unrelated sessions
   remain live, owned workers release the device, and a new explicit request can
   acquire it. Compare app data/settings with baseline and `changes` metadata.
   A rename accepted before disconnect may leave the complete new pairing file;
   before rename the old file must remain. A staging file can remain. Never
   automatically delete it or retry an ambiguous installation to hide failure.

Expected authorized changes (trust, certificate/device registration, installation,
Wi-Fi debugging) are not automatically rolled back. Unexpected data loss,
settings change, reboot, persistent USB failure, stuck worker/lease, secret
exposure or unexplained result is **FAIL**: stop that scenario and block release
claims for the affected path until investigated. Preserve only redacted evidence;
do not perform an automatic recovery action on either phone.

## Evidence and release rule

For each commit/APK + Android/iOS/cable combination, maintain a private record:

```text
Build/commit/APK digest:
Android model/build; iPhone model/iOS build; cable:
Automated host gate / Android instrumentation: PASS | FAIL | NOT_RUN
Scenario and explicitly approved changes:
Baseline versus after: trust / Wi-Fi / app data / unrelated sessions / USB:
Observed residual changes and expected changes agree: YES | NO | UNKNOWN
Result: PASS | FAIL | NOT_RUN
Reviewer/date; redacted evidence location:
```

All required rows must pass before claiming that **the tested configuration**
passed physical acceptance. No hardware, skipped stage or unknown comparison is
`NOT_RUN`, never pass. Revalidate after transport/pairing/install changes or new
iOS/Android builds; beta results do not certify stable releases or vice versa.
The current virtual environment supplies no physical acceptance evidence.

한국어 요약: 자동 검사 성공과 실기기 안전성 확인을 구분합니다. 작업 전후의 설정·
데이터·USB 연결·다른 세션을 비교하고, 예상 밖 변화가 있으면 즉시 중단합니다.
실기기 미검증 상태에서 “기기에 문제가 없다”고 표시하거나 안내하지 않습니다.
