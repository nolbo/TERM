# AOSP AVF runtime driver

`AospAvfPlatformDriver.kt` is the privileged deployment implementation of the
public-SDK-neutral `AvfPlatformDriver` interface. It is intentionally excluded
from this repository's ordinary Gradle build because it imports the AOSP System
API package `android.system.virtualmachine`.

## Product integration

Compile this source into the app or privileged runtime service with the target
AOSP system SDK and platform APIs enabled. The owning package must be
platform/OEM signed and granted both:

```text
android.permission.MANAGE_VIRTUAL_MACHINE
android.permission.USE_CUSTOM_VIRTUAL_MACHINE
```

Set the product manifest's `application android:name` to
`dev.terminal.runtime.avf.system.AospRuntimeApplication`. It implements the
public runtime module's `RuntimeBackendProvider` seam and selects
`AospAvfPlatformDriver` inside the `:vmhost` process. The public build has no
provider and therefore retains `PlatformProbeAvfDriver`. No reflection or
hidden-API bypass is permitted.

## Fixed initial image mapping

The signed Debian image contract maps disks in this exact order:

```text
vda = system.img  (read-only)
vdb = home.img    (writable)
vdc = config.img  (read-only)
```

The guest image's kernel command line and `fstab` must agree with that mapping.
`getOrCreate` reuses an existing AVF instance and ignores a replacement config;
therefore image upgrades must use an explicit reset/migration flow rather than
silently replacing boot or disk files.

`VirtualMachine.run()` is asynchronous. The driver waits for the AVF ready
callback, then retries the `droid-sessiond` vsock port (40500) for a bounded
period. A successful `RuntimeBackend.start()` consequently means the VM has
reported ready, while a successful `connectGuestAgent()` means sessiond has
accepted a vsock connection.

The capability bridge uses a second, dedicated guest-agent port: `40501`.
`VmRuntimeService` connects it only after the VM is ready and dispatches its
versioned `BRIDGE` frames through `AndroidCapabilityBroker`; it is never
multiplexed with PTY bytes on port `40500`.

Forwarded guest services use raw vsock streams only inside the runtime owner:
the AOSP driver opens the requested non-privileged port and wraps its descriptor
as `GuestByteStream`; `LocalhostPortForwarder` binds the corresponding host
listener exclusively to `127.0.0.1`.

The public runtime provisions one host-owned directory under
`files/shared-vms/<vm-id>` for a future virtiofs export. It is deliberately not
mounted by this first driver until the product supplies the matching AOSP
virtiofs service/socket configuration and guest fstab entry. This prevents an
incorrect claim that a regular directory is already visible in the guest.
