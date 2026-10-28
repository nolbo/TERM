# AVF guest and image updates

Current guest builds omit `sessionResumeVersion` and serve session frames directly
on host-only vsock 40500. The daemon drains PTYs and collects exits without an
active viewer, retaining bounded lossy output tails with explicit gap events.
The recovery service, Python/native relay and Unix relay endpoint are retired.
A verified component update removes the obsolete TERM unit/symlink/helpers;
user files and distribution services remain unchanged. The existing component
receipt selects the target's missing/zero session capability, including a
transition from an installed `sessionResumeVersion=1` base.

The APK retains its legacy relay adapter for unchanged installed images. Updating
the APK alone cannot deliver the new guest architecture or its performance gains.
Use the existing stopped-Environment copy/health/activation transaction to apply
a verified component or image update. No live disk is overwritten. Matching
kernel hashes permit component updates; full-transition rules below still apply.
The new OutputGap handling requires the matching approved APK; an older host
cannot safely reset incomplete terminal parsers after lost output. Input and
output buffers are process-local and never migrate as persistent history.

The follow-up guest refinement requires the same verified component/image path:
independent pidfd child reaping, nonfatal input overload, close-on-exec PTY masters,
reused loop storage and chunked media encoding reside in `droid-sessiond`, not
the APK. It requires Linux 5.3+ (the pinned guest is 6.12), adds no service or wire
version, and does not itself publish an image or alter installed environments.

Cooperative media conversion, staged-output resize cancellation and output-queue
reuse also require the updated guest component. The matching APK clears incomplete
multipart Kitty transfers on both terminal screens at an OutputGap. Conversion
uses ordinary Kitty m=1/m=0 and APC cancellation; no extra daemon, port or manifest
capability is added. Existing guest paths, permissions and the source-file unlink
policy are unchanged; an interrupted conversion leaves its source available.

The host output-delivery refinement needs only the updated APK and applies to
both direct and legacy session connections: output pressure no longer blocks RPC
responses, and host-local gaps preserve parser/resize ordering. Guest resize
cancellation replies and batched 21 KiB file reads need the verified updated
sessiond component. They change no manifest capability or wire opcode, and do not
publish or activate an image as part of a source-only update.

## Decision and compatibility

Format v1 `home.img` contains the root OverlayFS upper/work trees. Format v2
contains a complete writable EXT4 root, with `system.img` retained as an immutable
baseline. A new base cannot safely reuse either old writable disk. Updates use
either a package installed on a copy of the existing state, or a new base with
fresh state and explicit migration. Originals are never attached writable to an
update VM. This also supports old images without a maintenance endpoint.

The saved Environment's Distribution image section first offers **Check for updates**.
It compares the installed base/component source with the APK-pinned manifest and
fetches only that bounded manifest when the pin differs. It never discovers an
unsigned remote catalog or downloads disks during a check. A matching source is
up to date even when the writable disk has changed. A verified component receipt
prevents offering the same component update again.

Only a differing, maintenance-capable target exposes one update action. Matching
kernel hashes and root formats select components, retaining the installed kernel/initrd/config and
base; a different kernel or root format selects the full image. Factory initrd/config disk hashes
can differ between builds without a semantic change, so they do not select a full
transition. A check failure or unsupported target exposes no update
action. An APK's catalog is the available version boundary; checking does not
claim to discover images absent from that APK. Checks are read-only and can run
while a VM is active. Applying the selected action continues to
require confirmation, a stopped non-protected VM, no live/disconnected sessions,
an APK-approved maintenance image, Internet and space for candidate/state copies.
The storage-specific WorkManager job owns downloads/progress/cancellation;
`:vmhost` owns maintenance and the storage preparation lease. Downloading does
not stop an existing VM. Environment/Profile/storage/normal VM identities remain.

`maintenanceVersion=1` is optional in image manifests and APK catalogs. Old
catalogs retain installation behavior; release APKs disable update actions until
an approved maintenance catalog is embedded. Debug APKs approve update checks
for every distribution present in their bundled catalog, without waiting
for its maintenance approval marker. This does not add missing guest features:
the service still requires `maintenanceVersion=1` in the actual pinned manifest
before starting maintenance. Missing catalogs remain unavailable, and both builds
keep checksum, stopped-VM, session, confirmation and state-copy checks. Source
changes do not update released catalogs or authorize publication. See `IMAGE_RELEASE.md`.

## Transfer and copy performance

The existing signed catalogs, gzip assets and raw manifest hashes remain compatible.
The worker calculates SHA-256 while expanding each downloaded artifact; a retry
rehashes any retained raw file before reusing it. The installer or VM service
still independently verifies the stored files at its ownership boundary. The
worker no longer repeats that entire stored-file scan just before the handoff.
The updater also leaves startup verification/receipt creation to driver `prepare`
instead of adding another full check immediately before it. Every normal or
maintenance boot retains the driver's immutable-artifact checks.

Compressed files and bounded HTTP metadata live in work-ID-specific sibling
cache directories, outside the installed environment. A strong ETag plus
`Range`/`If-Range` permits resuming only an exact, matching `Content-Range` with
identity HTTP encoding. A full 200 response replaces the old prefix; missing or
weak validators restart the transfer. TLS redirects stay credential-free HTTPS.
Neither an ETag nor cache metadata authenticates image contents: the approved raw
SHA-256 remains mandatory. Gzip failures and raw checksum mismatches evict that
cached artifact. Interrupted transfers and already verified raw files survive
WorkManager retries; completion, cancellation and terminal failure clean both
staging and cache. New work removes only inactive attempts for the same storage.
The compressed cache temporarily requires additional disk space.

Update state and immutable-file copies use Android's public `Os.lseek` with Linux
`SEEK_DATA`/`SEEK_HOLE` on the same descriptor used for reads. Copies skip reported
holes, retain the exact logical length, flush output and always create independent
inodes. An unsupported filesystem falls back to the existing bounded zero-aware
copy; invalid ranges and I/O errors fail instead of publishing partial output.
VM-stop/storage leases still exclude writers. Writable state is never hardlinked.
`TermAvfImage` logs logical bytes, bytes read and whether extent queries were used.
Android instrumentation covers the real syscall path, sparse allocation, byte
identity and independence; it does not establish end-to-end AVF update timings.

## Guest components

The factory builds `term-guest.deb` for Debian/Ubuntu and `term-guest.pkg.tar` for
Arch, containing TERM binaries, scripts, units/drop-ins and TERM-owned policy.
Authentication is signed APK catalog → pinned manifest → system disk hash →
local package. This is not an unsigned remote feed or a claim that a `.deb`
has its own independent signature. Dependencies use normal signed repositories.

A dedicated VM boots the approved new image, attaching old system RO as vdd and
a copied old state RW as vde. The fixed maintenance operation mounts that old
base with its matching copied upper for v1, or bind-mounts the copied complete
EXT4 root for v2, then installs the local package. The host passes the verified
source format explicitly; the guest never guesses from writable disk contents. APT refreshes
repositories and dependencies. Arch performs full `pacman -Syu` to avoid partial
upgrades; local overwrite permission names only exact producer-managed files.
An Arch component update can therefore upgrade distribution packages too.

The resulting generation keeps original kernel/initrd/system/config plus updated
state. A host-created receipt binds component capabilities to the approved source
manifest. Immutable hashes remain unchanged; kernel-dependent capabilities still
come from the base manifest. Component updates require an identical kernel hash and root format,
rechecked by the service before a maintenance boot.

## Full image transition

The new image starts with pristine writable state. Migration identifies additional
manual packages relative to the recorded factory baseline, falling back to the
old base's installed-package database for legacy images. Names are bounded and
validated before reinstalling through the new distribution's repositories.
Unavailable local/custom packages or repository failures abort the update.

It copies `/home`, `/root`, `/srv`, `/opt`, `/workspace`, preserving Linux
file/link/owner/mode/xattr/hardlink semantics without crossing mounts. `/etc` uses
a conservative three-way merge: retain edits when new defaults match the old
base, accept identical edits, abort conflicts. Local `passwd`, `group`, `shadow`
and `gshadow` databases instead merge by account name and field, with three-way
set merging for supplementary members and group administrators. Independent
package-created accounts and user memberships therefore do not conflict merely
because they share a file. Account deletion versus editing, conflicting fields,
UID/GID collisions or renumbering, missing primary groups and inconsistent shadow
entries abort without changing the original generation. Numeric ownership from
both old user data and candidate package files must remain valid; migration does
not renumber accounts or guess at password conflicts. All four databases are
validated before private temporary files replace candidate entries, retaining
candidate owner, mode and extended attributes. Account database symlinks are
rejected. Generated `passwd-`, `group-`, `shadow-`, `gshadow-` backups and
`.pwd.lock` stay candidate-local instead of being replayed as user configuration.
Other `/etc` conflicts still require resolution. TERM-managed files, fstab,
machine-id and resolver use new policy. Application data under `/var/lib`,
`/var/spool`, `/var/www` and user `/usr/local` content are also copied. Distribution
package state (`dpkg`, `apt`, `pacman`, `ucf`), systemd/dbus state and TERM receipts
under `/var/lib` retain the new image's state. TERM-managed files retain the new
approved content. Copying preserves ownership, permissions, Linux xattrs/ACLs,
symlinks, hardlinks across selected trees and special files, skips sockets and
does not cross filesystems or follow destination symlink parents. A source link
cannot replace a directory containing managed files.

For v1 sources, direct state-disk data outside `upper`, `work` and `lost+found`
is copied to the new root's `/.droid/state`; v2 sources retain that same directory.
This includes explicitly configured Docker/ReDroid paths. The source merged
OverlayFS is read as a filesystem, so raw whiteouts/upper trees are not copied as
root data. Application database format upgrades are not performed by the copier;
package reinstall/configuration and candidate health failures abort activation.
Other customized root paths remain in the complete retained old generation.

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
offline factory boots must adopt the package, test account merging against
disposable copies of their distribution's real databases, and emit
`TERM_AVF_ACCOUNT_MERGE_OK` followed by `TERM_AVF_GUEST_PACKAGE_OK`.
Actual Android maintenance/validation boots, owner-death recovery and restore
still require a privileged AVF target. Stock emulator checks do not prove them.
