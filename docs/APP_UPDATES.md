# TERM app updates

## Source and releases

The application is **TERM**. Source, verification, signing and releases share
the public [nolbo/TERM](https://github.com/nolbo/TERM) repository. Installed apps
read public release assets without a GitHub token. The default branch is `main`;
local development and prerelease preparation use `dev`.
see [repository consolidation](REPOSITORY_MIGRATION.md) for the preserved history
and former private source archive.

There are two independently installed release apps: **main → stable** uses
`dev.terminal.app`, and **dev → prerelease** uses `dev.terminal.app.prerelease`.
Both retain the persistent release signing key. Debug builds keep
`dev.terminal.app.debug`. Each installed release checks only its own build
channel; the update screen displays that channel without a channel selector.
Stable and prerelease can coexist with separate Android app data, settings,
execution environments and update caches.

Older development APKs used `dev.terminal.app`, the same ID as stable. They are
preserved in place: installing the new prerelease app creates a separate, empty
private data directory and does not replace or uninstall them. No automatic data
migration occurs. Users may explicitly use supported environment backup/restore
to copy environments; this does not imply automatic migration of all app settings
or live sessions. Historical APKs, manifests and release tags remain unchanged.

### Version ordering and compatibility

Base-version promotion follows [Versioning policy](VERSIONING.md). The base
describes product changes; Android installation ordering still uses the shared
`versionCode` sequence below. Increasing the base does not authorize publication.

Independent branch commit counts and commit timestamps can collide or regress.
Published CI APKs retain the existing shared sequence:
`versionCode = 1000000 + TERMINAL_VERSION_CODE_OFFSET + GITHUB_RUN_NUMBER`,
shared by both branches of `android.yml`. The repository variable
`TERMINAL_VERSION_CODE_OFFSET=70` preserves ordering after moving from
`nolbo/terminal`, whose final published code was `1000070`.
`tools/updates/version-code.py` validates the sequence and Android integer range. GitHub's [workflow run number](https://docs.github.com/en/actions/reference/workflows-and-actions/variables)
increments on new executions and is retained by retries. The epoch exceeds
legacy main commit-count codes. Do not reset/recreate this workflow or lower
the epoch or offset; any future sequence migration must exceed all published codes.

The version name is `<base>+<main|dev>.<code>.<12-character source SHA>`.
Legacy schema-1 main manifests without a channel remain readable, and legacy
stable clients can consume new main manifests/names. Development metadata is
never accepted when checking the stable channel, or vice versa. Metadata must
also match the exact package for that channel. A higher code cannot cross the
stable/prerelease package boundary; version ordering applies within each app.
An older release reports that a newer release is needed, rather than claiming
the installed app is up to date. No uninstall or downgrade is attempted. A
same-code different commit is rejected as non-forward. Legacy development
manifests for `dev.terminal.app` cannot update the new prerelease package.

Local builds use `origin/dev` on the dev branch, otherwise `origin/main`, unless
`TERMINAL_UPDATE_CHANNEL` overrides it. Local release variants use the selected
ref's full commit count as their fallback code, so they cannot replace a newer
CI release APK; an explicitly supplied `TERMINAL_UPDATE_VERSION_CODE` must be a
positive Android integer. Debug outputs use the independent allocator below.
Complete Git history is still required. No local count-based build is published
automatically. For main/dev push events, CI sets `TERMINAL_UPDATE_REVISION` to
the event's checked-out `GITHUB_SHA`; it never labels older checked-out source
with a newer queued revision. Other branches and PRs are verified, not published.
Manual dispatch defaults to `publish_channel=none`: on `dev` or `main` it builds
and verifies a signed APK without publishing it. The workflow in `dev` checks
the installed-app signing identity and exact approved AVF catalog for both release
channels. Imported `main` retains the former source repository's 0.1.0 stable
code, which predates the approved-catalog integration. Changing the default
branch and importing stable history does not promote development features or
publish a stable APK.

## Publishing

### Debug installations

`dev.terminal.app.debug` has an independent install sequence allocated by
`tools/updates/debug-version-code.py`: the maximum of seconds since 2020-01-01,
the previous locally allocated code plus one and the supplied minimum plus one.
`TERMINAL_DEBUG_MIN_VERSION_CODE` can set that minimum to the installed code.
Debug variant outputs use this value even when a release workflow code is supplied.

Android updates also require a compatible signing identity. The local retained
Debug keystore and the default key generated on a fresh CI runner are different;
a larger version code cannot resolve that mismatch. A CI review artifact is
therefore not automatically a replacement for an existing Debug installation.
Compare actual APK certificates before recommending installation. A local test
handoff can re-sign the verified APK with the matching retained key and check
that all non-signature contents remain identical. Existing apps and data must
not be deleted to work around a signing mismatch. Release signing remains
independent of Debug signing.

### Release channels

An explicitly authorized release can publish either channel from shared `dev`
history without merging or changing `main`. Dispatch `android.yml` on `dev` with
`publish_channel=main` for stable or `publish_channel=dev` for prerelease:

```sh
gh workflow run android.yml --ref dev -f publish_channel=main -f notify=true
gh workflow run android.yml --ref dev -f publish_channel=dev -f notify=true
```

Run only the command for the requested channel. The selector rejects explicit
publication from other refs/events and requires the event's source SHA to belong
to the fetched `origin/dev` history. The selected channel determines the app
package, version name, Korean `<channel>-latest.md` notes, artifact name and
release tag; the exact event SHA remains the source revision. Neither source
branch nor its remote tracking ref is repointed to choose a release channel.
Push publications and explicit manual publications targeting the same channel
share a serialization group and the forward-only versionCode sequence. Stable
and Preview builds may run concurrently: they have distinct packages,
commit-specific tags and discovery pointers. The default manual verification
mode does not publish, and all existing build, signing, Rootless, USB safety and
approved-catalog checks remain required before a manual publication.

After canonical verification, app tests and the USB safety gate, CI uses the
persistent release key to build one APK. `tools/updates/create-manifest.py`
derives size/hash and version information from that built APK and its Gradle
metadata, rejecting a commit/version or channel/package mismatch. The artifact contains:

- `TERM.apk`
- `update.json` (schema 1, application ID, channel, source commit, code/name, minimum SDK,
  exact bytes and SHA-256)
- the existing native corresponding-source bundles

CI verification uses JDK 17. The signed release step switches only its Gradle
runtime to JDK 21: AGP 9.3's bundled lint parser crashed on JDK 17 with
`NoSuchMethodError: java.util.List.removeLast()` in the first main publication
run. That Java API was introduced in 21; release packaging had already passed
on the local JDK 21 host. Explicit Java/Kotlin compilation targets and JVM
module toolchains remain 17. Release lint stays enabled; no Android SDK, Gradle
or library upgrade is needed for this runtime compatibility fix.

`tools/updates/publish.sh` creates `main-<full source SHA>` stable releases and
`dev-<full source SHA>` prereleases in the
same repository. New commit-specific release tags target the exact source
commit from `update.json`. Historical distribution tags and release assets keep
their original targets and URLs; their source commits are now reachable through
the imported history. Uploads remain drafts until complete;
published APK/source assets are never overwritten. The channel build job is
serialized through publication and compares codes so a slower old build cannot become latest.
Dev prereleases always set `latest=false`, leaving stable `/releases/latest`
untouched. A separate **dev-latest** prerelease holds only the mutable
`update.json` discovery pointer, never an APK. Newer dev builds upload that
manifest after the commit-specific prerelease is complete, then update its
title/code. Retrying after a pointer upload failure preserves the published APK
and completes only the pointer. First-pointer uploads stay drafts until ready.
Only this discovery manifest is an exception to release asset immutability.

After a channel publication succeeds, the Android workflow can pass the actual
published tag to the reusable Discord notifier. Channel pushes retain their
existing notification behavior. Manual app publication enables `notify=true` by default, so both TERM and
TERM Preview announce a successful release. An explicit `notify=false` suppresses
a deliberately silent publication; image-only workflows retain their separate
opt-in notification setting. Verification-only and failed runs do not notify.
An authorized missing-notification recovery can dispatch
`discord-release.yml` on `dev` with its required `release_tag` input, for example
`gh workflow run discord-release.yml --ref dev -f release_tag=main-<source SHA>`.
The notifier requires an existing published release and sends its current notes
without rebuilding or republishing the APK. This explicitly sends another
message, so use it only when a notification is requested.

The TERM repository needs these GitHub Actions secrets:

- `TERMINAL_SIGNING_KEYSTORE_BASE64`: existing PKCS12 keystore, alias `terminal`.
- `TERMINAL_SIGNING_STORE_PASSWORD`: its password.

App and image publication use the repository's short-lived `GITHUB_TOKEN`.
Only publishing jobs grant `Contents: write`; AVF version-sequence lookup also
requires `Actions: read`. A separate `TERMINAL_UPDATES_TOKEN` is no longer used.
No token or signing credential is compiled into an APK or included in artifacts.
The release certificate SHA-256 remains
`7f639e70cc1969415beab27cfb10db6d10bf553b1f956a2f9d68d55ebf3785ba`;
signed packaging rejects a different certificate.

### Publication time and cache policy

Channel pushes check that signing/publication secrets and Korean channel notes
exist before downloading toolchains or compiling. This checks presence only;
the signing and publication steps still validate the credentials when used.
The `android.yml` run-number sequence is shared by both channels; the migration
offset preserves ordering across the old and new repositories.

`gradle/actions/setup-gradle@v4` restores Gradle dependencies and local build-cache
entries; the debug verification and signed release commands enable `--build-cache`.
Rust registry/git dependencies and the Android/host connector Cargo target
directories are restored with OS, host architecture, Rust 1.89.0, NDK version and
recipe/source keys. Compatible previous target caches can seed incremental
compilation after source changes; Cargo still runs with `--locked`. Both pushes
and manual runs on main/dev can save caches after successful verification;
PRs and other refs only restore them. Cargo cache paths exclude
signing material, APKs, update manifests and native distribution archives, and the
temporary release keystore remains outside Gradle's cached user home.
Rootless input hashes and both APK payload checks continue to run on every build.

Native CLI production is an `Exec` task, whose outputs are not stored by Gradle's
build cache. A separate exact-match cache retains `android-runtime/build/native-cli`,
including its pinned upstream archives, build stamps, binaries, licenses and
Cargo vendor sources required for the corresponding-source bundle. The key
includes OS, host architecture, Rust/NDK versions and all Native CLI recipe inputs
plus the Android compiler wrapper; there is no fallback to another recipe's
native build. The build script still checks source hashes and its compiler/recipe
signature, and APK payload/source-bundle checks still run. A changed recipe or
toolchain gets a fresh cache. Signed APKs, manifests and keystores are never part
of this native cache.

The AVF workflow's catalog-bearing APK job restores the same Native CLI and Rust
caches as the app workflow and can save caches from verified dev push/manual
runs. It also saves its Gradle cache instead of remaining permanently read-only.
This avoids rebuilding the same native tools for each distribution's review APK;
the image factory's boot, catalog, APK and public-download gates remain required.

APK/source artifacts use `compression-level: 0` to avoid recompressing ZIP/gzip
payloads. These Actions archives, including the safety report, are best effort:
artifact storage quota or upload failures do not stop channel publication.
The channel job publishes directly from its verified signed build directory,
without downloading an Actions artifact in a second job. Canonical verification,
app tests, the USB safety gate and release lint
remain publication prerequisites. The USB gate remains sequential because it
executes Gradle and reads shared test-result files. Publication stays serialized
within each channel and retains immutable assets and the forward-only latest-code
comparison. Stable publication controls `/releases/latest`; Preview only controls
`dev-latest`, so neither channel needs to wait for the other's build.

Timing baseline on 2026-10-24: [app run 40085673519](https://github.com/nolbo/TERM/actions/runs/40085673519)
spent 12m07s in its build job (7m11s debug build/tests, 4m11s signed build, 5s USB
gate). [The simultaneous second channel run](https://github.com/nolbo/TERM/actions/runs/40085676631)
waited about 12 minutes behind the former shared lock before its own 12m07s job.
[Image run 40083253273](https://github.com/nolbo/TERM/actions/runs/40083253273)
already reused its kernel and spent 20m07s in image production, 8m26s in the APK
job and 1m05s publishing. These are pre-change measurements, not a speedup claim.
The short USB and artifact steps are retained; native cache reuse and independent
channel scheduling address the observed larger costs. Continue using
`kernel_run_id` for an unchanged verified kernel and `reuse_candidate` only for
an exact, already verified draft as described in [IMAGE_RELEASE.md](IMAGE_RELEASE.md).

The first run must populate caches; cache eviction or changed toolchains can also
cause a cold build. Compare step durations on subsequent channel runs to measure
the actual improvement. Local validation cannot establish GitHub runner timings.
For a local signed build with caching, use `bash tools/build-release.sh --build-cache`.

## App behavior and boundaries

**Settings → App updates → Check for updates** retrieves
`https://github.com/nolbo/TERM/releases/latest/download/update.json` for stable,
or `https://github.com/nolbo/TERM/releases/download/dev-latest/update.json` for
prerelease. The installed app determines this endpoint; prerelease builds show
an instability warning.
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
initial fixed-key update channel. Debug builds do not offer release updates and
remain a separate installation; the app never uninstalls another app to install
an update.

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
Public download verification requires a published release, and installer completion
requires a release-signed test installation; do not claim those
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
