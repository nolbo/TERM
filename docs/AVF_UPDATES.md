# AVF guest and image updates

## Decision and compatibility

`home.img` contains the root OverlayFS upper/work trees, including packages and
configuration. A new lower image cannot safely reuse that state. Updates use
either a package installed on a copy of the existing state, or a new base with
fresh state and explicit migration. Originals are never attached writable to an
update VM. This also supports old images without a maintenance endpoint.

Both actions belong to the saved Environment's Distribution image section. They
require confirmation, a stopped non-protected VM, no live/disconnected sessions,
an APK-approved maintenance image, Internet and space for candidate/state copies.
The storage-specific WorkManager job owns downloads/progress/cancellation;
`:vmhost` owns maintenance and the storage preparation lease. Downloading does
not stop an existing VM. Environment/Profile/storage/normal VM identities remain.

`maintenanceVersion=1` is optional in image manifests and APK catalogs. Old
catalogs retain installation behavior; release APKs disable update actions until
an approved maintenance catalog is embedded. Debug APKs approve both update
actions for every distribution present in their bundled catalog, without waiting
for its maintenance approval marker. This does not add missing guest features:
the service still requires `maintenanceVersion=1` in the actual pinned manifest
before starting maintenance. Missing catalogs remain unavailable, and both builds
keep checksum, stopped-VM, session, confirmation and state-copy checks. Source
changes do not update released catalogs or authorize publication. See `IMAGE_RELEASE.md`.

## Guest components

The factory builds `term-guest.deb` for Debian/Ubuntu and `term-guest.pkg.tar` for
Arch, containing TERM binaries, scripts, units/drop-ins and TERM-owned policy.
Authentication is signed APK catalog → pinned manifest → system disk hash →
local package. This is not an unsigned remote feed or a claim that a `.deb`
has its own independent signature. Dependencies use normal signed repositories.

A dedicated VM boots the approved new image, attaching old system RO as vdd and
a copied old state RW as vde. The fixed maintenance operation mounts that old
base with its matching copied upper and installs the local package. APT refreshes
repositories and dependencies. Arch performs full `pacman -Syu` to avoid partial
upgrades; local overwrite permission names only exact producer-managed files.
An Arch component update can therefore upgrade distribution packages too.

The resulting generation keeps original kernel/initrd/system/config plus updated
state. A host-created receipt binds component capabilities to the approved source
manifest. Immutable hashes remain unchanged; kernel-dependent capabilities still
come from the base manifest. Component updates require an identical kernel hash.

## Full image transition

The new image starts with pristine writable state. Migration identifies additional
manual packages relative to the recorded factory baseline, falling back to the
old base's installed-package database for legacy images. Names are bounded and
validated before reinstalling through the new distribution's repositories.
Unavailable local/custom packages or repository failures abort the update.

It copies `/home`, `/root`, `/srv`, `/opt`, `/workspace`, preserving Linux
file/link/owner/mode/xattr/hardlink semantics without crossing mounts. `/etc` uses
a conservative three-way merge: retain edits when new defaults match the old
base, accept identical edits, abort conflicts. TERM-managed files, fstab,
machine-id and resolver use new policy. Databases under `/var/lib`, custom
`/usr/local` content and other root paths are not automatically migrated. They
remain in the complete old generation and need application-aware migration.

This favors explicit conflicts over silently losing configuration. Alternatives
rejected: old-upper reuse mixes package databases; copying package databases to
a new base breaks package ownership; a guest-only endpoint cannot bootstrap old
images. Costs include full-image download even for component updates, temporary
disk space, maintenance boots and potentially manual conflict/data migration.
Different distribution identities cannot be crossed.

## Activation, rollback and recovery

After maintenance the service stops/deletes the temporary VM, boots the resulting
candidate normally, waits for sessiond, requests fixed health/sync and proves
the validation VM stopped. Neither temporary boot exports Android storage or
private folders. Unknown platform state retains attached files and blocks cleanup.

Copying and hashing stay outside the registry lock. Final activation rechecks
image generation/tombstones under that lock, then moves only known artifacts and
host receipts. A forced journal/directory fsyncs protect interrupted moves.
Startup verification rejects a pending journal. Recovery restores the original
complete generation only after proving the normal VM stopped.

History lives inside the Environment's `.avf-versions`, included in storage
measurement and explicit removal. Restore swaps both base and state, retaining
the generation being left. It never merges post-update writes. Separate
destructive confirmation deletes all history without touching active files.
There is no automatic history garbage collection.

The service owns `.avf-maintenance` after consuming verified worker staging, so
worker cancellation cannot remove attached files. Next launch/update/rollback/
removal stops/deletes only the reserved maintenance VM, recovers any journal,
then deletes unpublished work. Original data survives failed package/config/
health checks. Cancellation after the final activation fence may leave an
already committed update with its previous generation retained.

Host tests cover move interruption, matching state rollback, rejected/corrupt
candidates, failed health/generation checks, opcode restrictions, package
construction and configuration/package selection validation. Both disposable
offline factory boots must adopt the package and emit `TERM_AVF_GUEST_PACKAGE_OK`.
Actual Android maintenance/validation boots, owner-death recovery and restore
still require a privileged AVF target. Stock emulator checks do not prove them.
