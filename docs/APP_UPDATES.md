# TERM app updates

## Source and releases

The application is **TERM**. Source, verification, signing and releases share
public [nolbo/TERM](https://github.com/nolbo/TERM). The default branch is `main`;
local development and prerelease preparation use `dev`. Main preserves the
former source repository's 0.1.0 stable code. See
[repository consolidation](REPOSITORY_MIGRATION.md) for the preserved histories.
Installed apps read public release assets without a GitHub token.

There are two explicit channels: **main → stable** and **dev → development**.
Both use application ID `dev.terminal.app` and the persistent release key.
The update screen stores the selected channel; its initial default comes from
the installed APK. A switch resets stale availability/installation state, and
is disabled while checking/downloading. It never changes runtime selection.

### Version ordering and compatibility

Independent branch commit counts can collide or make returning from dev to
main impossible until main catches up. Commit timestamps also allow collisions
and clock regressions. Published CI APKs therefore use
`versionCode = 1000000 + TERMINAL_VERSION_CODE_OFFSET + GITHUB_RUN_NUMBER`,
shared by both branches of `android.yml`. The repository offset is 70 to preserve
ordering after the migration. `tools/updates/version-code.py` validates the
sequence and Android integer range. GitHub's [workflow run number](https://docs.github.com/en/actions/reference/workflows-and-actions/variables)
increments on new executions and is retained by retries. The epoch exceeds
legacy main commit-count codes. Do not reset/recreate this workflow or lower
the epoch or offset; any future sequence migration must exceed all published codes.

The version name is `<base>+<main|dev>.<code>.<12-character source SHA>`.
Legacy schema-1 main manifests without a channel remain readable, and legacy
stable clients can consume new main manifests/names. Development metadata is
never accepted when checking the stable channel, or vice versa. A higher code
can update across channels; an older channel reports that a newer release is
needed, rather than claiming the installed app is up to date. No uninstall or
downgrade is attempted. A same-code different commit is rejected as non-forward.

Local builds use `origin/dev` on the dev branch, otherwise `origin/main`, unless
`TERMINAL_UPDATE_CHANNEL` overrides it. Their fallback code is the selected
ref's full commit count, so they cannot replace a newer CI APK; an explicitly
supplied `TERMINAL_UPDATE_VERSION_CODE` must be a positive Android integer.
Complete Git history is still required. No local count-based build is published
automatically. For main/dev push events, CI pins the selected remote ref to
the event's checked-out `GITHUB_SHA`; it never labels older checked-out source
with a newer queued revision. Other branches and PRs are verified, not published.
Manual dispatch on `main` or `dev` also verifies a signed APK without publishing
or notifying Discord. Both branches check the existing certificate; current
`dev` additionally checks the exact approved image catalog. Imported 0.1.0 main
predates catalog embedding and does not require that resource.

## Publishing

After canonical verification, app tests and the USB safety gate, CI uses the
persistent release key to build one APK. `tools/updates/create-manifest.py`
derives size/hash and version information from that built APK and its Gradle
metadata, rejecting a commit/version mismatch. The artifact contains:

- `TERM.apk`
- `update.json` (schema 1, application ID, channel, source commit, code/name, minimum SDK,
  exact bytes and SHA-256)
- the existing native corresponding-source bundles
- `RELEASE_NOTES.ko.md`, copied from committed `docs/release-notes/<channel>-latest.md`

CI verification uses JDK 17. The signed release step switches only its Gradle
runtime to JDK 21: AGP 9.3's bundled lint parser crashed on JDK 17 with
`NoSuchMethodError: java.util.List.removeLast()` in the first main publication
run. That Java API was introduced in 21; release packaging had already passed
on the local JDK 21 host. Explicit Java/Kotlin compilation targets and JVM
module toolchains remain 17. Release lint stays enabled; no Android SDK, Gradle
or library upgrade is needed for this runtime compatibility fix.

`tools/updates/publish.sh` creates `main-<full source SHA>` stable releases and
`dev-<full source SHA>` prereleases in the
same repository. New tags target the exact source commit. Historical public
tags and asset URLs keep their original targets. Uploads remain drafts until complete;
published APK/source assets are never overwritten. The build/publication job is
serialized and compares codes so a slower old build cannot become latest.
Dev prereleases always set `latest=false`, leaving stable `/releases/latest`
untouched. A separate **dev-latest** prerelease holds only the mutable
`update.json` discovery pointer, never an APK. Newer dev builds upload that
manifest after the commit-specific prerelease is complete, then update its
title/code. Retrying after a pointer upload failure preserves the published APK
and completes only the pointer. First-pointer uploads stay drafts until ready.
Only this discovery manifest is an exception to release asset immutability.

The TERM repository needs these GitHub Actions secrets:

- `TERMINAL_SIGNING_KEYSTORE_BASE64`: existing PKCS12 keystore, alias `terminal`.
- `TERMINAL_SIGNING_STORE_PASSWORD`: its password.

Publication uses the repository's short-lived `GITHUB_TOKEN` with job-scoped
`Contents: write`; a separate `TERMINAL_UPDATES_TOKEN` is no longer required.
No credentials are compiled into an APK or included in artifacts. Signed
packaging verifies the existing certificate SHA-256:
`7f639e70cc1969415beab27cfb10db6d10bf553b1f956a2f9d68d55ebf3785ba`.
The reusable Discord workflow runs only after successful push publication;
manual verification sends no notification. Prepare Korean channel notes under
[RELEASING.md](RELEASING.md) before an explicitly authorized publication.

## App behavior and boundaries

**Settings → App updates → Update channel → Check for updates** retrieves
`https://github.com/nolbo/TERM/releases/latest/download/update.json` for stable,
or `https://github.com/nolbo/TERM/releases/download/dev-latest/update.json` for
development. Development releases show an instability warning.
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
In-progress downloads use a bounded private `filesDir/app-update-download`
staging APK, outside the provider's paths. Android can evict cache files even
while a stream has them open; staging therefore moves into the installation
cache only after hash, package and signing verification. Failed staging is
deleted and an interrupted download is overwritten on retry.
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
