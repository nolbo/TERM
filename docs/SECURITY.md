# Security

The Linux guest is an untrusted execution environment. No guest process may
receive arbitrary Android Binder handles, host UIDs, private app paths, raw file
descriptors, or unrestricted `ContentResolver` access.

The Phase 0 runtime has no guest-to-Android capability calls. Future Android
integration follows this route:

```text
guest CLI → droid-agent → vsock → Android capability broker
```

Each broker request must be authenticated, protocol-validated, capability-
checked, Android-permission-checked, and optionally confirmed by the user.
Capability grants are persisted per VM and are independent of Android runtime
permissions.

The current `PlatformProbeAvfDriver` only checks whether the device advertises
AVF and whether signature permissions were actually granted. It does not try to
bypass the platform requirement with hidden API reflection.

## Executable policy

Android 10 and newer disallow `execve` of files in a writable app home for apps
targeting API 29 or newer. This app targets API 35. `NativeExecutableLoader`
therefore resolves only a small allow-list from the APK-installed native
library directory. Rootfs downloads are treated as untrusted data; neither a
downloaded PRoot binary nor an arbitrary file selected through SAF is executed.
The only downloaded Rootless artifact is a catalog-pinned Debian rootfs archive:
the installer limits its size, verifies SHA-256 before extraction, rejects path
escapes and unsafe links, and activates only a complete staging tree.

Rootless bind paths are constructed by the host from fixed app-owned paths.
Guest-provided bind paths, arbitrary host file descriptors and the full app
data directory are not exposed.
# Security boundary

## Capability broker

Android APIs are reached only through `android-bridge`'s
`AndroidCapabilityBroker`. A guest request is evaluated against a grant keyed
by the host-selected VM instance ID before any Android service is called.
`ASK` is deny-by-default until a host permission UI is implemented; it never
silently becomes an allow decision.

The initial broker supports only clipboard text, HTTP(S) URL opening, and
notifications. It validates size limits and URL scheme/host, returns typed
results, and never returns Android Binder objects, `Context`,
`ContentResolver`, or host file descriptors to the guest.

Capability grants remain independent from Android OS permissions and service
availability. For example, a guest notification grant does not bypass the
user's Android notification setting.

`VmRuntimeService` exposes only typed capability/grant names through local
AIDL for the future host permission UI. Invalid values are ignored, grants are
stored per host-selected VM instance, and guest RPC cannot alter its own grant.
