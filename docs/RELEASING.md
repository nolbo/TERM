# Release preparation

The user's release or prerelease deployment request defines the scope. Follow the Git and authorization policy in [AGENTS.md](../AGENTS.md); required supporting images are part of that deployment request and do not require separate approval.

## Versioning and patch notes

App base-version decisions MUST follow `docs/VERSIONING.md`. Assess the complete
unreleased scope, use the highest applicable increment, and record the reason.
Build numbers and commits alone do not trigger a base-version increase.

Every release, including prereleases, MUST have Korean patch notes in
`docs/release-notes/<release-id>.md`. Use the actual version or release tag as the
release ID, including any prerelease suffix. Create the directory when preparing
the first release under this policy.

When preparing a release, Codex MUST:

1. review the changes since the preceding release of the same artifact, or the
   included scope for an initial release;
2. write the patch notes before completing release preparation or publishing;
3. include the release ID, release date, and affected artifacts or runtimes;
4. focus on user-visible additions, changes, removals, and fixed bugs, including
   security changes when they affect users;
5. document required upgrade or migration steps, compatibility changes, and
   known limitations when applicable;
6. keep build commands, CI results, commit selection, implementation details,
   and other developer-facing information out of user-facing patch notes;
   report validation separately in the task report or an internal release record;
7. commit the patch notes with the task-owned release changes and include their
   path in the final report.

Write each patch-note paragraph and list item on one source line. Do not wrap
sentences manually to fit a line-width limit or insert unnecessary hard breaks.
Use line breaks only where the Markdown structure requires them.

Patch notes MUST describe changes actually included in the release, not planned
work or an unfiltered commit log. Preserve notes for previous releases; correct
them only when needed for accuracy. A release task is incomplete without its
patch notes. This requirement does not itself authorize tagging, pushing, or
publishing; the Git policy and user-requested scope still apply.

## Required image updates

For every app release or prerelease deployment, compare the complete included scope with the currently approved image's provenance and capabilities. Check guest binaries, services, boot or mount scripts, kernel/initrd requirements, filesystem configuration and manifest feature declarations. A host feature that needs guest support must have that support in the distributed image; updating host code or image-producer source alone is insufficient.

When an image update is required, complete these steps within the deployment request without asking for separate image approval:

1. Produce an image containing the required changes from shared `dev` history under [IMAGE_RELEASE.md](IMAGE_RELEASE.md). A previously verified candidate may be reused only if its provenance and contents include all required image changes.
2. Run the required image verification and boot checks, and write and commit Korean notes for the image's immutable release ID before publication. Report device-only validation and any evidenced platform limitations separately; source inspection and offline smoke tests do not prove live AVF behavior.
3. Publish the verified image artifacts to the configured distribution destination. Verify unauthenticated public downloads, the manifest pin and all artifact hashes against the producer outputs before promoting a catalog.
4. Commit the verified `image/catalog-approved.properties` on `dev`, perform the authorized normal fast-forward pushes and complete the app's signing/publication CI using that catalog. Check the released APK actually embeds the intended image catalog; a candidate APK or a local catalog edit is insufficient.
5. Verify the published app and image are available, and report the app release, image release, catalog pin, validation results and any upgrade or migration requirements.

An app deployment is incomplete while required image changes remain unpublished or the released APK points to an older image without the required support. If no image update is needed, retain the existing approved image. Preserve installed environments and user data: publication and catalog promotion do not automatically replace existing guest images. Document whether existing environments need a supported image transition or a new installation to receive the feature.
