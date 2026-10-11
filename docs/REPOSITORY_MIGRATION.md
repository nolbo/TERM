# TERM repository consolidation

## Decision and preserved history

On 2026-10-11, source and distribution were consolidated into public
[nolbo/TERM](https://github.com/nolbo/TERM). The former private
`nolbo/terminal` repository is retained as an archived backup.
TERM's default branch is `dev`; new development and verification use that branch.
The local `origin` points to TERM and `legacy-source` identifies the backup.

Before consolidation, source `dev` ended at
`830d072cd119e978025fcd17c241eeda9e06443b`, and its `main` ended at
`fa20f3345141c5e2670ee72161fb4d559fcf156d`. Distribution `main` ended at
`f658a1a0bdc4df9b7f9e40daf0fd660a19f1576e`.
The previously unrelated distribution history was merged into source `dev`.
Both histories and original commit IDs remain reachable; commits were not replayed.
Source `main` was already an ancestor of source `dev`.

Public `main` retains the former distribution tip during this cutover.
It is now an ancestor of unified `dev`. A later, explicitly authorized stable
promotion can merge/fast-forward `dev` into `main`. Consolidation itself does not
publish an app or image release or promote development changes to stable.

Existing release IDs, assets, download URLs and public tag targets remain unchanged.
The two repositories had different targets for `v0.1.0-rootless.1`.
The public tag retains `5fa050cbc86ad7abb98306b54be4d4f414d7c027`;
the former source tag is preserved as `migration/source-v0.1.0-rootless.1`,
targeting `93187285428a9796cc472b978eabd390fb47cf35`.
New commit-specific app release tags target their actual source commit.

## Signing, updates and CI

The application ID remains `dev.terminal.app` and the release certificate SHA-256 is
`7f639e70cc1969415beab27cfb10db6d10bf553b1f956a2f9d68d55ebf3785ba`.
The existing keystore and password are transferred inside the former source CI
using Libsodium sealed-box encryption for TERM's Actions public key.
Only ciphertext leaves that runner; GitHub decrypts it when a TERM job uses the secrets.
The temporary transfer workflow is removed after migration.

TERM's Android workflow starts a new GitHub run-number sequence.
Repository variable `TERMINAL_VERSION_CODE_OFFSET=70` carries the final published
source code `1000070` forward. New codes are
`1000000 + offset + android.yml run_number`, shared by main/dev and Rootless signing.
The first new workflow run has code `1000071`; manual verification can consume a
code without publishing it. AVF review APKs use the latest Android run number,
or zero before the first run. They do not create a second publication sequence.
Do not lower the offset or reset the workflow without another ordering migration.

App and image jobs publish within TERM using the repository `GITHUB_TOKEN` and
job-scoped permissions. The cross-repository `TERMINAL_UPDATES_TOKEN` is no longer
required. The existing Discord notification workflow and its secrets are retained;
app/image publishers invoke it after successful publication because releases
created with `GITHUB_TOKEN` do not trigger another release-event workflow.
Manual verification sends no notification. The inherited Termux debug-release
attachment/deletion and JitPack publication workflows are retired.
Former source Actions are disabled to prevent duplicate publishers.

Manual Android verification builds the canonical debug checks, app/update tests,
USB host safety gate and signed release APK. It checks the release certificate
and exact approved AVF catalog but does not publish a channel release.
The existing `avf-debian-20261011` image and approved catalog remain valid;
repository consolidation changes no guest or runtime code and needs no new image.
Installed apps and environments require no migration or reinstallation.

## Alternatives and limitations

Moving releases into the former private source repository would change public
download locations or require changing its visibility. Keeping distribution and
source separate would retain a cross-repository publisher credential and two
histories. Keeping TERM preserves all existing installation/update endpoints and
provides one public source, build and release location, at the cost of publishing
the original source history and retaining legacy tags whose targets describe the
old distribution repository. Historical release metadata still identifies the
original source revision, now reachable from `dev`.

## Verification record

The final [manual Android verification run #3](https://github.com/nolbo/TERM/actions/runs/38139642499)
passed on `05c78e4a1971b873a7a055bc03f87e33d62a11d4`, with version code `1000073`.
It ran `:android-app:assembleDebug`, `:android-app:testDebugUnitTest`,
`:android-runtime:testDebugUnitTest`, `:image:test`, the USB host safety gate,
37 Python image tests and 28 update tests, then built the signed release APK.
The APK's certificate matched the existing key above and its embedded catalog
matched `image/catalog-approved.properties` byte for byte. Publication and
notification jobs were skipped for this manual verification.

The first two runs passed builds/tests but failed the added certificate-output
parser. The captured `V2 Signer:` output now has regression coverage alongside
numbered and SDK-range signer labels. The final local update suite has 29 tests,
including the Rootless tag/draft/request arrival race and rejection of a request
for another source commit before signing. The extra test and this record change
no app/runtime code, so the successful canonical build is retained.

GitHub metadata comparison preserved all 47 existing releases, 341 assets and
47 published tag targets. The additional migration tag preserves the source
tag; all 48 local tags match TERM. The archived source backup ends at the same
verified code commit `05c78e4a1971b873a7a055bc03f87e33d62a11d4`, with Actions disabled.
Device/UI/privileged AVF checks were not repeated because runtime code and image
contents did not change in this consolidation.
