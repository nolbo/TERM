# Shared directories

Shared directories are independent of logical Workspaces and Environment filesystems. Native exposes selected host paths through `TERM_SHARED_FOLDERS`; Rootless binds selected directories at `/mnt/term-shares/<name>` when a new session starts. Connections are a convenience within the same Android UID, not filesystem security isolation. Running local sessions retain their bindings until exit.

## AVF sharing retired

AVF does not support shared directories or Android primary-storage (`/sdcard`) exports. The runtime configuration accepts no host-directory exports. It sends no sharing boot parameters, creates no SharedPaths and opens no SFTP/SSHFS broker streams. Settings exclude AVF from folder connections and internal-storage permission controls, and Binder rejects new AVF connections. Saving an old folder removes its retired AVF references while preserving its UUID and data. Folder-based copies to/from AVF are also retired. The independent Android share-to-Linux file-copy endpoint remains available without a shared-directory connection.

The previous implementation coupled VM readiness to optional mounts, broker ownership, permission revocation and reconnect workers. Removing these dependencies lets sessiond start without waiting for host filesystem services. New image production omits sharing capability markers, mount/broker services, SSHFS package dependencies and the virtiofs initrd requirement. Session transport recovery remains enabled and uses its existing generic replay transport. Distro FUSE support for containers or user-installed tools is not removed.

Keeping a hidden sharing switch was rejected because it would retain the same startup and ownership failure paths. This intentionally removes shared project filesystems from AVF; files inside its own Linux disks remain available.

## Existing environments and data

Installed disks and the approved image catalog are not replaced by this source change. On the next stopped boot, the driver refreshes the saved VM configuration to remove old exports and broker parameters. Old guest scripts are inactive without those parameters. A running VM with a persisted sharing lease cannot be adopted: explicitly stop and restart that Environment. Ordinary unshared VM reconnects remain available. No active VM is restarted merely by editing a folder.

Legacy host leases remain until the platform proves their VM stopped. They still protect folder deletion and public-to-private restoration. Storage-permission revocation can stop a pre-upgrade storage-sharing VM; a shared boot fence prevents that cleanup from racing a new unshared boot. No new sharing leases or host workspace directories are provisioned. Cleanup removes only old socket/index artifacts, never shared-folder or Environment data. Failed stop or cleanup retains protection.

A future approved image release is needed to remove obsolete services from guest disks. Component updates and full transitions retire TERM's old sharing services and sessiond drop-ins on the staged filesystem while retaining user data and distro FUSE configuration. Existing images remain usable without enabling sharing.

## Restoring previous public backing

Old `androidStorage=true` folders retain their latest public source until an explicit Save confirms **앱 전용 저장소로 전환**. The verified copy requires the actual all-files grant and idle sessions, persisted VM owners, file jobs and host writers under the preparation reservation. It rejects missing sources, links and special files; limits are 2 GiB, 100,000 entries and 64 levels.

The runtime copies and rehashes the public tree into `.private-<UUID>`, retains the old private tree as `.original-<UUID>`, syncs the parent and publishes private metadata last. A synced `.shared-folder-private-restores/<UUID>` journal permits retrying only unpublished private copies. The public source is retained; the stale private backup is never substituted for missing current data. Explicit deletion after restoration removes private data and its private backup and leaves the retained public source intact.

Legacy `/workspace` discovery/import/ZIP workers and interrupted-copy cleanup remain for compatibility; settings do not expose these retired controls. Environment and Workspace deletion do not remove independent shared-directory data.

## Validation boundaries

JVM tests cover legacy lease reconnect rejection, verified-stop cleanup, preservation of folder data and revocation serialization. Python tests cover share-free guest installation, maintenance cleanup, manifests and required normal-boot evidence. Android tests cover editor pruning, rejected folder-copy requests and interrupted-job cleanup. A compatible privileged ARM64 AVF target is still needed to validate real VM boot and the upgrade stop/start path. Source tests and an ordinary Android emulator do not establish that result.
