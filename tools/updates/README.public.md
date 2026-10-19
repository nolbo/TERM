# TERM

**Terminal Environments with Runtime Multiplexing**

Public source and Android APK releases for TERM. App versions follow the
main/development branches. Source commit and channel are recorded in each
release’s `update.json`; source and previous distribution history are preserved
in `main` and `dev`. The default branch is `main`; local development uses `dev`.
See the root README for current build instructions.

Download **TERM.apk** from [Releases](https://github.com/nolbo/TERM/releases).
Stable and development releases are published after verification. The app also checks releases under **Settings →
App updates**, without requiring a GitHub account.

Stable **TERM** (`dev.terminal.app`) follows **origin/main**. **TERM Preview**
(`dev.terminal.app.prerelease`) follows **origin/dev** and appears as a prerelease.
Both apps can be installed together, with separate settings, profiles and
environments. Each app checks only its own update channel; development releases
may be unstable. CI version codes retain one shared workflow sequence, with
forward-only updates checked within each installed package.
The **dev-latest** prerelease contains discovery metadata only; download APKs
from their commit-specific releases. No developer token is required in the app.

Each APK is signed with the same app signing key. Android asks for confirmation
before installation; a debug-signed app cannot be replaced with a different
release signing key. Save live terminal work before updating: Android may stop
the app and its runtime during APK replacement. Normal updates retain app data.

Older development releases used the same package as stable TERM. They stay
installed with their data when Preview is installed separately. Preview starts
with its own empty data area; there is no automatic transfer or uninstall. To
transfer an environment, explicitly export a backup from the old app and import
it into Preview. Keep the old app until the imported environment is verified.

Releases include integrity metadata and native corresponding-source bundles.
No signing keys, CI credentials, private device data, or pairing records belong
in this repository. Distribution issues and source-material requests can be
filed through this repository's Issues page.
