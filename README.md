# TERM

**Terminal Environments with Runtime Multiplexing**

Public Android APK distribution for TERM. App versions follow the source
mainline commit history. The mainline commit is recorded in each release's
`update.json`; this repository's Git history contains distribution information.

Download **TERM.apk** from [Releases](https://github.com/nolbo/TERM/releases).
The first release will appear after the publisher is configured and a mainline
build passes verification. The app also checks releases under **Settings →
App updates**, without requiring a GitHub account.

Each APK is signed with the same app signing key. Android asks for confirmation
before installation; a debug-signed app cannot be replaced with a different
release signing key. Save live terminal work before updating: Android may stop
the app and its runtime during APK replacement. Normal updates retain app data.

Releases include integrity metadata and native corresponding-source bundles.
No signing keys, CI credentials, private device data, or pairing records belong
in this repository. Distribution issues and source-material requests can be
filed through this repository's Issues page.
