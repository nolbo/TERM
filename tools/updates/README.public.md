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

Stable updates follow **origin/main**. Development updates follow **origin/dev**
and appear as prereleases. The app offers an explicit Stable/Development channel
selector; development releases may be unstable. CI version codes share one
workflow sequence, and an older channel cannot downgrade an installed app.
The **dev-latest** prerelease contains discovery metadata only; download APKs
from their commit-specific releases. No developer token is required in the app.

Each APK is signed with the same app signing key. Android asks for confirmation
before installation; a debug-signed app cannot be replaced with a different
release signing key. Save live terminal work before updating: Android may stop
the app and its runtime during APK replacement. Normal updates retain app data.

Releases include integrity metadata and native corresponding-source bundles.
No signing keys, CI credentials, private device data, or pairing records belong
in this repository. Distribution issues and source-material requests can be
filed through this repository's Issues page.
