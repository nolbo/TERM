# Rootless accelerated prereleases

The `v0.1.0-rootless.N` source tag runs the signing job in `android.yml`.
It does not run the ordinary main/dev publisher or replace `dev-latest`.
All preparation and commits stay on `dev`; the source tag identifies that commit.
New candidates use the separate `dev.terminal.app.prerelease` package (TERM Preview).
Previously published tags and APKs keep their original package and are not rewritten.

1. Complete the runtime checks and performance report, and commit Korean notes
   under `docs/release-notes/<tag>.md` before creating the source tag.
2. Read the next run number for the existing Android verification workflow.
   Its versionCode is `1000000 + TERMINAL_VERSION_CODE_OFFSET + run_number`
   (`TERMINAL_VERSION_CODE_OFFSET=70` in TERM); compute it with
   `python3 tools/updates/version-code.py --offset 70 --run-number <number>`. Verify the actual number when the
   tag run starts; concurrent workflow activity can change the expected number.
3. Build with the pinned accelerated payload and these explicit inputs:

   ```sh
   TERMINAL_UPDATE_CHANNEL=dev \
   TERMINAL_UPDATE_VERSION_CODE=<code> \
   TERMINAL_UPDATE_REVISION=<full-source-commit> \
   ./gradlew -PappBaseVersion=<tag-without-v> \
     -ProotlessAcceleratedPayload=<payload-directory> \
     -PterminalUnsignedRelease=true :android-app:assembleRelease --console=plain
   ```

   Use the existing host JDK suitable for release lint (JDK 21 on the ARM64 host).
   The unsigned option is explicit; normal release tasks still require signing.
4. Push the corresponding source tag to `nolbo/TERM`, then create a **draft**
   prerelease there with that existing tag. Upload
   `signing-input.tar.gz` with exactly two regular files at its root:
   `unsigned.apk` and `request.json`. The request contains `commit`, `tag`,
   integer `versionCode`, and `apkSha256`. Do not put credentials in the bundle.
5. The signing job waits up to 20 minutes for the draft and signing request.
   The job checks
   the request against its event commit, tag and shared run sequence, checks APK
   package/version/alignment, then signs using the existing protected CI secrets.
   It uploads the signed APK, `SHA256SUMS` and `signing-verification.txt` into the
   draft and deletes the unsigned input. It never publishes the draft itself.
6. Download and independently check the APK hash, signing certificate against
   the previous release, version and embedded runtime payload. Attach source
   archives and the performance report/raw measurements. Publish the draft with
   `--prerelease --latest=false` only after validation succeeds.

Source and prerelease signing now share `nolbo/TERM`; do not reuse a historical
distribution tag for a different source commit. The existing
`v0.1.0-rootless.1` tag is preserved; its old source tag is available as
`migration/source-v0.1.0-rootless.1`.
A failed signing run leaves the draft unpublished. Correct the evidence or
artifact and rerun the same workflow run; do not silently change the versionCode.
