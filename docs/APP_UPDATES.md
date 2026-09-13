# TERM app updates

## Source and distribution

The application is **TERM**. Its public distribution repository is
[nolbo/TERM](https://github.com/nolbo/TERM). The source repository can remain
private: installed apps read only public release assets, with no GitHub token.

Android `versionCode` is the full commit count of `origin/main`; `versionName`
is `<base>+main.<count>.<12-character SHA>`. Git history must be complete and
append-only for monotonically increasing Android updates. A same-count rewrite
is not treated as an installable forward update. Native and Rootless remain
independent of update availability; an update error never selects a runtime.

For main push events, CI pins its local `origin/main` ref to the event's checked
out `GITHUB_SHA`. A newer push arriving during an older build cannot give that
older APK a newer revision's identity. Branch and PR APKs retain the version of
their target mainline and are not published as app updates.

## Publishing

After canonical verification, app tests and the USB safety gate, CI uses the
persistent release key to build one APK. `tools/updates/create-manifest.py`
derives size/hash and version information from that built APK and its Gradle
metadata, rejecting a commit/version mismatch. The artifact contains:

- `TERM.apk`
- `update.json` (schema 1, application ID, main commit, code/name, minimum SDK,
  exact bytes and SHA-256)
- the existing native corresponding-source bundles

CI verification uses JDK 17. The signed release step switches only its Gradle
runtime to JDK 21: AGP 9.3's bundled lint parser crashed on JDK 17 with
`NoSuchMethodError: java.util.List.removeLast()` in the first main publication
run. That Java API was introduced in 21; release packaging had already passed
on the local JDK 21 host. Explicit Java/Kotlin compilation targets and JVM
module toolchains remain 17. Release lint stays enabled; no Android SDK, Gradle
or library upgrade is needed for this runtime compatibility fix.

`tools/updates/publish.sh` creates `main-<full source SHA>` releases in the
distribution repository. This is a distribution tag, not a Git object imported
from the private source repository. Uploads remain drafts until complete;
published assets are never overwritten. The publication job is serialized and
compares version codes so a slower old build cannot become the latest release.

The source repository needs these GitHub Actions secrets:

- `TERMINAL_SIGNING_KEYSTORE_BASE64`: persistent PKCS12 keystore, alias `terminal`.
- `TERMINAL_SIGNING_STORE_PASSWORD`: its password.
- `TERMINAL_UPDATES_TOKEN`: a fine-grained GitHub token restricted to **nolbo/TERM**,
  repository permission **Contents: read and write** (metadata read is automatic).

Create the publisher token in GitHub **Settings → Developer settings → Personal
access tokens → Fine-grained tokens**, selecting **Only select repositories →
TERM**. Do not reuse an unrestricted personal CLI token. See the
[GitHub token creation documentation](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).
Register it through a local hidden prompt:

```sh
gh secret set TERMINAL_UPDATES_TOKEN --repo nolbo/terminal
```

The existing source repository `GITHUB_TOKEN` cannot publish to another
repository. No publisher token is compiled into an APK, sent to the app, or
included in artifacts. Missing publication credentials fail that job. Until
the publisher is configured and these changes reach main, no public release
is available. Repository creation alone does not publish an APK.

## App behavior and boundaries

**Settings → App updates → Check for updates** retrieves
`https://github.com/nolbo/TERM/releases/latest/download/update.json`.
The first-release 404 is shown as “No updates have been published yet,” not as
an up-to-date claim. Newer supported versions can be downloaded; older versions
are not offered. APK URLs are constructed from validated commit IDs and fixed
repository/asset names, never accepted from arbitrary remote URLs.

The application-owned controller serializes checks/downloads and survives
screen recreation and detach. It is not a shell/VM/session owner. Process death
loses update progress honestly and requires another download; cached files are
not automatically declared ready after restart. Downloads are capped at
128 MiB, manifest responses at 16 KiB, with HTTPS-only GitHub/asset redirects,
bounded redirect count and connection/read/transfer deadlines.

The app enforces exact file size and SHA-256, checks the APK's package/version,
minimum SDK and **every current installed signing certificate**, and rejects
unsigned/differently signed APKs. Signing-key rotation is not supported in this
initial fixed-key update channel. A debug installation needs a matching debug
key or an explicit first migration; the app never uninstalls it automatically.

Verified APKs stay in a dedicated app cache directory. The non-exported
FileProvider exposes only that directory, using a temporary URI read grant to
Android's installer. No broad storage permission or guest capability is added.
Same-UID Native/PRoot processes are not an OS security sandbox; Android's own
package installer remains authoritative for signature validation at install.

Download does not grant installation permission. The user confirms installation
and, if needed, enables this app's **Install unknown apps** system setting, then
returns and taps Install again. Android still asks for installation confirmation.
The app never requests silent installation or calls runtime terminate/stop.
APK replacement can make Android stop all app processes and interrupt active
sessions; the confirmation explicitly asks users to save work. A normal signed
update preserves app data and Linux files, not live process continuity.

## Validation

```sh
python3 -m unittest discover -s tests/updates -p 'test_*.py'
./gradlew :android-app:assembleDebug :android-app:testDebugUnitTest \
  :android-runtime:testDebugUnitTest :image:test --console=plain
./gradlew :android-app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.terminal.app.AppUpdateDeviceTest \
  --console=plain
```

Unit checks exercise malformed manifests, version/SDK selection, exact streaming
size/hash and signing identity rejection. Publisher tests check first/forward
publication, late older builds, draft retries, immutable published assets and
fail-closed API errors. Device checks exercise Android's
real signing parser, FileProvider directory bounds and screen recreation.
Published APK download and installer completion require the publisher token,
main integration and a release-signed test installation; do not claim those
checks passed from local unit tests or repository creation.

Initial implementation validation passed the canonical tasks plus app unit
tests (6 update cases), publisher/manifest tests (9 cases), and 3 on-device
instrumentation checks. Replicant confirmed Settings navigation, the live
first-release 404 status and preservation of that state after screen re-entry.
An isolated release build signed with a temporary fixture key passed APK v2
signature verification and manifest generation; it did not replace the locally
built production APK or publish that fixture. The host SDK's ADB bridge and
Replicant's filtered Gradle test invocation were incompatible with this host;
the device checks instead ran through Android MCP instrumentation.
