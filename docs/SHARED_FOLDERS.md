# Shared Directory

## Ownership and paths

Shared directories are independent of logical Workspaces and Environment disks. `SharedFolderRegistry` stores validated UUID identities, ASCII names and explicit Environment connections; the normal backing is `files/shared-folders/<UUID>`. Renaming changes the mount name, not the UUID path. Environment/Workspace deletion preserves this storage. Only explicit folder-data deletion removes it after proving all session owners, persisted VM users and host writers idle and file jobs finished.

Native receives selected host paths in `TERM_SHARED_FOLDERS`. Rootless binds selected UUID directories at `/mnt/term-shares/<name>` and pins their directory capabilities for session lifetime. Native and PRoot share the Android UID; connections do not create Android UID isolation. No app-private parent/root is exported. Name/connection changes apply to new Native/Rootless sessions and the next AVF VM boot.

The existing separate create/edit Activities retain drafts across recreation and save only through UI-authorized `:vmhost` Binder calls. No legacy `/workspace` import, retained-data picker or ZIP-export option is exposed. Older workspace directories, backup formats and migration recovery remain compatible and preserve data in place; they do not recreate automatic `/workspace` bindings.

## App-private AVF sharing

A verified `shareBrokerVersion=1` or `2` image and an authorized non-protected custom VM are required. The app initiates a boot-owned control connection and one duplex connection per selected UUID to guest vsock 40506. The guest accepts host CID 2 only, validates the complete UUID/name manifest, then runs passive SSHFS as UID/GID 1000 on `/mnt/term-shares/<name>`. SSHFS speaks SFTP v3 over those streams; no SSH daemon, TCP listener, guest-to-host connection or guest-selected host command exists. The guest confirms readiness only after every mount exists and its root responds; sessiond waits for that confirmation.

Each host server pins exactly one app-private folder. No-follow parent walks and leaf checks reject path traversal, special files, hardlinked content and host symlink following. Symlink targets remain literal guest paths; the guest resolves them. Host paths/errors never cross the wire. Ordinary file/directory operations, metadata, rename replacement, fsync and statvfs are supported. Data and directory/attribute caches are disabled and writes are synchronous; host updates are observed on later guest reads. Cross-runtime advisory locks, hardlinks, device files and arbitrary ownership changes are unsupported. This is a shared project/data filesystem, not the guest root filesystem.

Limits: 128 folders per VM, 256 global folder streams, 128 handles per stream/256 globally, four concurrent host operations, 256 KiB packets and 128 KiB data requests. Live files have no TFT1 whole-file size/deadline limit. Broker v1 disconnects fail the affected VM closed. Broker v2 replaces transient sockets with bounded byte replay while retaining SSHFS/SFTP handles; a 60-second owner lease bounds authority. Expired or failed shares deny I/O, with root-owned mode-000 underlying mount guards preventing writable fallback. The VM and unrelated sessions continue only after guard proof; startup/guard failures remain fatal. See `PROTOCOL.md`. Leases persist before VM run and survive owner death/connection edits. Folder data stays protected until the platform proves the VM stopped and the app's worker I/O has ended. A process-wide owner registry enforces this even when a separate verifier/revocation driver handles the stop.

Connected folders create no virtiofs `SharedPath` or index. This avoids Android 17's `crosvm_vu_fs` app-private-data denial. Optional whole-`/sdcard` sharing retains its separate virtiofs image capability, Environment choice and actual all-files grant. Broker-only VMs need no storage grant. Existing images without a supported `shareBrokerVersion` receive an explicit error when folders are connected; installed disks and the approved catalog are never replaced automatically. The 20261020-r1 images deliver this service through the image catalog embedded in subsequent APK builds.

## Restoring the previous public backing

The earlier Android 17 workaround copied AVF-connected folders to primary shared storage. Existing `androidStorage=true` folders remain on that latest public source until an explicit Save confirms **앱 전용 저장소로 전환**. The one-time verified copy needs storage permission and idle sessions, VM owners, file jobs and host writers under the preparation reservation. It rejects missing sources, links and special files; limits are 2 GiB, 100,000 entries and 64 levels.

The runtime copies/rehashes the public tree into `.private-<UUID>`, retains the old private tree as `.original-<UUID>`, syncs the parent and publishes private metadata last. A synced `.shared-folder-private-restores/<UUID>` journal permits retrying only unpublished private copies after interruption. Retry copies the latest public source again; the stale private original is never used as recovery data. The public source is retained. After success, use and folder deletion need no storage permission. Explicit deletion removes private data and its private backup; it leaves the retained public source intact.

## Explicit AVF copies

The existing non-root `droid-agent --file-transfer` endpoint at host-CID-only vsock 40505 remains separate from live mounts. It uses TFT1 framing, streaming SHA-256, no-follow descriptors, fsync and no-replace publication. A running host-selected VM and current folder/Environment connection are required; copying never starts a VM. Guest copies live under `/home/droid/term-transfers/<name>/<relative-path>` and do not synchronize automatically.

Files are limited to 64 MiB, host jobs to 120 seconds, guest socket I/O to 30 seconds and concurrent jobs to two. Cancellation/revocation closes only that operation. Originals and prior destination files remain intact; a verified copy committed before a lost acknowledgement may remain and retry never overwrites it. UI detachment does not cancel service-owned jobs. Details are in [PROTOCOL.md](PROTOCOL.md).

## Validation boundaries

Protocol/JVM tests cover framing, capability versions and broker shutdown/rejected/late connections. Android instrumentation exercises the real JNI/SFTP filesystem path, live updates, rename, permissions/timestamps, traversal/symlink/hardlink rejection, public-to-private retry and editor behavior. Standard OpenSSH SFTP interoperability is tested over an explicitly enabled loopback fixture. Python tests cover guest manifest parsing, fixed unprivileged SSHFS arguments and boot-token binding; producer tests cover manifest/publication verification and guest installation.

These checks do not establish an actual AVF FUSE mount. A privileged compatible ARM64 AVF target and a broker-capable guest image are still required to verify boot readiness, two VMs, host-death isolation/guard enforcement, real FUSE semantics and stop/lease cleanup. Normal image boot production also requires host `/dev/vhost-vsock`; it must fail preflight when unavailable.
