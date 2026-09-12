# Native / Rootless USB access

## Shared iPhone CLI tool environment

Native and Rootless both support `terminal-usb list`, `iphone pair|info|unpair`,
`iphone ls|get|put`, and `tooling list|check|start|status|watch|select|cancel`. Each selected-device action uses
its own approved capability and Android USB permission. The `idevice-check`
host tool proves fresh internal usbmux clients can be supplied through an
anonymous connector while Android remains the physical owner. No USB FD,
connector or pair record is returned to either terminal.

This is tooling infrastructure, not an application SideStore/IPA installation
screen or a public usbmuxd endpoint. The registered isideload recipe uses the
generic **USB → Host tool inputs** screen for account/password/2FA, never the
Rootless PTY. Team and explicit certificate choices can use that screen or the
CLI. Async operations survive watch-client disconnect and UI navigation;
explicit cancellation, grant/session revocation and detach terminate/reap them.
Process death loses the operation and old temporary artifacts are removed on
the next service start, without resuming secrets or device writes. See
[IPHONE_TOOLING.md](IPHONE_TOOLING.md) and the
[connector build guide](../tools/iphone/connector/README.md).

## Scope and ownership

The implementation provides Android-brokered raw bulk/interrupt transfers, HID
report helpers, recognized USB serial drivers, an explicit PyUSB backend, serial
PTYs, apt-ADB transport, standard MTP/PTP files and Android-mounted removable
storage folder access. These are bounded compatibility paths, not kernel drivers.
It does not support every USB device or
unchanged libusb applications. `VmRuntimeService` owns `UsbBroker`, the local
socket, grants and Android USB connections. Each new Rootless PTY receives
host-generated credentials and one bind of the APK-bundled Python client.
UI detach/recreation never closes USB work. Shell exit, explicit session
termination, device detach and revocation invalidate the relevant grants.
CLI socket closure releases its interfaces; process death loses connections
honestly, without automatic restoration.

```text
Rootless terminal-usb → authenticated local socket → UsbBroker
  → raw/HID interface transport / pinned USB serial driver → UsbManager → USB device
```

The alternative of passing USB descriptors directly into Rootless was rejected
because it bypasses the host capability boundary. Kernel USB drivers and FUSE
mounts are unavailable in PRoot. Raw/CDC need no native ELF. The iPhone path adds
an APK-built, allowlisted Android worker, not a writable-data executable, root
requirement, AVF dependency or automatic distribution package installation.

## Usage

1. Use a USB Host-capable Android phone, a data-capable cable/OTG adapter and
   adequate power. Start a **new Rootless session** after updating the APK.
2. Python 3 is required. If missing, install it through the distribution:
   `apt install python3` (Debian/Ubuntu) or `pacman -S python` (Arch).
3. Run `terminal-usb list`. It returns descriptors, opaque attachment IDs and the
   requesting session ID, without Android device paths or serial numbers.
4. Run `terminal-usb serial DEVICE_ID --baud 115200` for a recognized serial device.
   For multiple ports, select one explicitly with `--port 0` (or another advertised
   port). `list` includes `serialPorts` with driver names and zero-based port indices.
5. On `APPROVAL_REQUIRED`, open **Settings → Terminal settings → USB devices**,
   inspect the session, VID/PID, interface and access mode, approve the request,
   and accept Android's device permission dialog. Run the command again.
   Pending requests expire after two minutes; denial never grants access.
6. Serial input defaults to ordinary line editing: Enter sends input, Ctrl-C exits the
   client without terminating the shell. DTR/RTS default to enabled; some boards
   reset on control-line changes. Use `--no-dtr`/`--no-rts` or consult `--help`.
   `--cbreak` sends keystrokes immediately while retaining Ctrl-C and restores the
   terminal on exit. This is not an arbitrary-binary interactive TTY mode; use a
   script for binary protocols. Drivers lacking DTR/RTS require the corresponding
   `--no-*` options. Initialization itself may reset hardware or change control lines.
7. The USB screen can revoke all USB grants for a displayed session. Reattaching
   a device requires fresh approval even if its USB address is reused.

## Raw protocol and limits

`terminal-usb raw DEVICE_ID INTERFACE_ID` holds one approved interface while
reading JSON objects, one per stdin line, and printing one result per line.
EOF releases the interface. Raw and CDC grants are separate. The legacy CLI uses
the first configuration and alternate zero. `RawInterface(..., configuration=N,
alternate=M)` selects other advertised settings with an exact separate grant;
interface claims never forcibly detach kernel drivers.

| `op` | Fields | Result |
| --- | --- | --- |
| `transfer`, IN endpoint | `endpoint`, `length`, `timeout` | `count`, hex `data` |
| `transfer`, OUT endpoint | `endpoint`, hex `data`, `timeout` | `count` (possibly partial) |
| `control`, IN type | `type`, `request`, `value`, `index`, `length`, `timeout` | `count`, hex `data` |
| `control`, OUT type | `type`, `request`, `value`, `index`, hex `data`, `timeout` | `count` |

Use actual endpoint addresses and documented device commands. JSON integers are
decimal; bit 7 determines IN/OUT. Bulk and interrupt endpoints are supported.
Class/vendor control requests must target the granted interface or its endpoints.
The sole standard-request exception is read-only HID report-descriptor retrieval
on a granted HID interface (`81/06/2200`, exact interface index). Other standard
and device-wide control requests, reset and isochronous transfers are not exposed.
Configuration changes use a separate explicit operation/approval described below,
never a raw standard control request. Raw permission allows arbitrary
command semantics within this scope, potentially including destructive commands.

Wire frames contain a four-byte big-endian length followed by a flat UTF-8 JSON
object with `version:1`, `token` and `op`. Replies contain `ok` and `result` or
`error`. `list` enumerates devices; `open` selects device/interface/serial mode;
`configure`, `read` and `write` implement the CDC driver. One socket owns one
connection. The Python client uses standard-library Unix sockets only.

Limits: 48 KiB frames; 16 KiB transfer payloads; 10–1000 ms transfer deadlines;
eight concurrent clients; 16 pending approvals; two-minute approval expiration;
30-second input inactivity. Raw clients must reconnect after longer idle periods.
Raw/CDC and serial device I/O run outside the shared broker lock. Raw operations
have a five-second cancellation watchdog, pre/post authorization checks and a
reservation retained until serving-thread cleanup. Expired operations never return
a late successful result. Revocation closes the connection and guest socket.
CDC configuration comprises two separately bounded one-second control requests.
Interrupt timeout closes its connection to prevent reuse of a pending request.
Android bulk errors cannot distinguish idle timeout from all other I/O errors;
the legacy raw caller must decide how to handle errors. `RawInterface.poll` keeps
an asynchronous IN request queued across idle polls: `None` means still pending,
and the next poll must use the same endpoint and buffer length. This avoids
discarding bytes between idle reads. An IN transfer or interrupt OUT cannot be
mixed with a pending poll; bulk OUT is allowed. The serial driver
reports idle reads as empty data and uses upstream connection checks. Successful
writes report their count; uncertain writes are never automatically replayed.

## Security and compatibility

The socket checks Android peer UID and a random per-session token. Guest-provided
session-create environment cannot override host credentials. Grants bind host
session ID, device attachment generation, interface and raw/CDC mode. Legacy CDC grants
cover control/data interfaces. New serial grants bind the host-selected driver
and requested port. FTDI/CP21xx ports can coexist only when one configuration,
all alternate-zero descriptors and interface IDs equal to their port indices establish
independent scopes. Other driver layouts reserve the whole device because their
initialization may issue device-wide commands. No raw control
requests are accepted through a serial-driver grant. Only the non-exported host UI/AIDL path approves
requests; permission callbacks recheck both prior host approval and Android USB
permission. Raw FDs, Binder and host paths never enter Rootless.

Native and PRoot share the app UID. These grants prevent accidental routing but
do **not** provide an OS sandbox against malicious same-UID processes that can
inspect sibling state. Do not claim isolation from tokens or socket names alone,
and do not expose the broker over TCP. Strong hostile-session isolation requires
a different OS boundary. See [SECURITY.md](SECURITY.md).

Block/FUSE mounts, isochronous audio/video, arbitrary kernel drivers and unchanged
libusb applications are not implemented. PyUSB requires explicitly selecting the
bundled backend. ADB, MTP/PTP and storage have the limits below. Raw transfer support
alone does not implement an undocumented device protocol.
The legacy CDC route still rejects ambiguous interfaces; the new serial route
uses the pinned upstream driver's recognized layouts. Android API references:
[USB Host](https://developer.android.com/develop/connectivity/usb),
[UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest).

## Generic serial and Python protocol API

The host builds [usb-serial-for-android 3.11.0](https://github.com/mik3y/usb-serial-for-android/tree/3.11.0)
from a SHA-256-pinned source archive. Included probers cover CDC ACM, FTDI,
CP21xx, CH34x, PL2303, selected GSM modems and Chrome CCD. This is driver coverage,
not a claim that every chip revision, clone, baud rate or composite layout was
physically tested. Unknown VID/PIDs are not force-mapped. Failed probing/opening
does not silently fall back to another driver or raw access. The build patches
all nine upstream forced claims to non-forcing claims and disables a debug-only
baud-rate test hook. MIT license text is packaged with the APK; see
`tools/usb-serial/README.md` for reproduction.

The flat `serial-open` request takes `device`, `driver`, `port`, `version`, `token`.
The host verifies its own probe before requesting approval. After opening, the
same socket accepts only `configure`, `read`, `write`, `status`, `ping`, `close`.
Configuration is mandatory before read/write. Configuration/read/write field
names are the same as the legacy CDC wire requests. Status returns supported and
current control-line names; unsupported device options fail explicitly. Every
request must carry the original token/version; additional fields are rejected.
`ping` returns `alive:true`; it can keep an otherwise idle stream alive.

Each serial operation has a 15-second watchdog including initialization and reply
delivery; requested read/write deadlines remain 10–1000 ms with upstream control
checks in addition. Client reply timeout is 20 seconds; input inactivity remains
30 seconds. Revocation/detach closes the transport and client socket, keeping the
device reservation until driver cleanup finishes. No automatic reconnection occurs.
Each stream buffers at most 16 KiB of surplus serial payload, preserving small reads
and stripping FTDI status bytes through the upstream driver. A write timeout returns
`count`, `timedOut:true`, `deliveryUncertain:true`: count is a lower bound, not proof
that no other bytes reached the device. The Python API raises `SerialWriteError`
instead of retrying. Closing/revoking cannot undo commands already delivered.

`terminal-usb run SCRIPT [ARGS...]` runs a local Python script with the bundled
`terminal_usb` API available, without installing another package or exposing FDs:

```python
import sys
from terminal_usb import SerialPort

with SerialPort(sys.argv[1], port=0) as port:
    port.configure(baud=115200, dtr=False, rts=False)
    port.write(b"status\r\n")  # Replace with a documented device-specific command.
    print(port.read(256, timeout=1000))
```

For non-serial equipment, `RawInterface(device_id, interface_id)` provides binary
`read(endpoint,size)`, `write(endpoint,data)` and `control(...)`. Use it as a context
manager. A short raw write is returned, not replayed. HID helpers are
`hid_report_descriptor(size)`, `hid_get_report(type,id,size)` and
`hid_set_report(type,id,data)`; callers interpret report layouts and include report
IDs in payloads as required by their device. These follow the
[USB HID 1.11 request definitions](https://www.usb.org/sites/default/files/hid1_11.pdf),
not an OS-wide keyboard injection or capture API. Kernel-owned HID interfaces may
remain unavailable because the app never forces their driver to detach. Report
descriptor reads are bounded and may be truncated at the caller's requested size.
This API does not create `/dev/ttyUSB*` devices. `serial-pty` supplies a local
`/dev/pts/*` byte stream for compatible existing tools; UART ioctls are not emulated.

The old JSON-line `raw` CLI and single-port CDC wire route remain compatible.
New serial grants are deliberately distinct; an old CDC/raw approval does not
silently authorize the broader device-level vendor driver.

Earlier serial-only validation (before the compatibility expansion below): canonical Android/runtime/image build and tests
passed; the USB subset has 23 unit tests, the Python client has 13 tests, and six
Android worker/broker instrumentation tests passed. Coverage includes strict
schemas, HID scope, small-read buffering, cancellation checks, uncertain-write
handling, port selection and denied real-socket requests. The bundled Python API
was also loaded through `terminal-usb run`, and its script/license assets were
verified in the APK. Android/Replicant confirmed settings navigation and the
USB-Host-unavailable state. These are **not** physical serial/HID transfer tests:
the available Android 15 target advertises accessory support but no USB Host.
Real adapter tests must cover each driver/port, line coding, DTR/RTS effects,
binary loopback, sustained throughput, unplug/revoke during I/O and UI recreation.

## Linux compatibility and explicit device settings

The client is a deterministic standard-library Python zipapp, still exposed by
one file bind. Additional modules are not shared host directories. Usage:

```sh
terminal-usb diagnostics
terminal-usb configuration DEVICE_ID 2
terminal-usb serial-pty DEVICE_ID --port 0 --baud 9600 --no-dtr --no-rts
terminal-usb serial DEVICE_ID --port 0 --baud 9600 --save-profile ./uart.json
terminal-usb serial-pty NEW_DEVICE_ID --profile ./uart.json
```

Configuration selection is device-wide, requires an idle device and its own
approval, and invalidates **all** old attachment grants/IDs after the attempt,
including failure. List again and approve the new ID. Raw open verifies the
actual active configuration before claiming. Alternate selection reserves one
interface and is bound to its exact configuration/alternate grant.

Profiles are explicit local JSON preferences (line settings and port only), not
automatic device matching, credentials or approval. Saving creates a new owner-only
file and refuses overwrite. `--profile` overrides the launch line/port options.
PTY line settings are fixed at bridge startup; changing a program's PTY baud,
parity or DTR/RTS does **not** change hardware. The relay preserves binary bytes,
bounds backpressure to 64 KiB, stays running until Ctrl-C/detach and never reconnects
or replays writes. PRoot must provide working `/dev/ptmx`/`/dev/pts` for this path.

Install `python3-usb` through apt to use the explicit backend:

```python
# Run with: terminal-usb run device_script.py
import usb.core
from terminal_usb import pyusb_backend
device = usb.core.find(idVendor=0x1234, idProduct=0x0001, backend=pyusb_backend())
if device is None:
    raise RuntimeError("Device not connected")
# Use documented, interface-scoped device commands, not arbitrary examples.
```

Descriptors project Android-exposed fields. USB strings, BCD versions, endpoint-0
packet size, total descriptor length and extra descriptors are unavailable
(zero/None/empty), not synthesized identifiers. Configuration descriptor power is
reported using the USB-2 unit convention because speed/BCD data is unavailable.
`set_configuration` verifies the selected configuration using the first interface's
approval; it does not silently change hardware. `get_configuration` also needs
an approved interface. Standard device-recipient control, reset, driver detach,
isochronous I/O and arbitrary libusb API compatibility remain unsupported. Read
timeouts are `USBTimeoutError`; retry a pending read with the same size, not a new
buffer shape. Transfers retain the broker's 16 KiB/10–1000 ms limits.

### apt ADB

```sh
apt install adb
terminal-usb adb DEVICE_ID
# In a second terminal, use the ADB_SERVER_SOCKET command printed by the bridge.
```

This reuses the installed ADB client/server, keys, authorization and shell/file
protocols. It does not implement another ADB server. The bridge recognizes exactly
one alternate-zero 255/66/1 interface with bulk IN/OUT, requiring raw-interface
approval plus the target's USB-debugging trust. It starts its own foreground ADB
child with a private `localfilesystem` server and one local transport socket, in
a 0700 temporary directory (sockets 0600). No public TCP listener or existing ADB
server is used/stopped. Ctrl-C/detach terminates only these owned children.
Linux parent-death signals also kill them if the bridge is forcibly terminated;
an environment that rejects this lifetime binding fails closed. The helper is
intended for the single-threaded CLI, not a threaded application's pre-exec hook.
ADB must support `-L localfilesystem`, `--one-device` and `connect localfilesystem`;
Debian ADB 34.0.4 was checked with a local mock peer. ADB packets are bounded to
1 MiB, header/payload USB boundaries and ZLPs are preserved, short writes abort
without replay. This is not evidence of successful USB debugging on a phone.
Same-UID process access remains possible; Unix socket permissions do not sandbox
hostile PRoot sessions. The bridge does not bypass target authorization.

### Standard MTP/PTP

```sh
terminal-usb mtp stores DEVICE_ID
terminal-usb mtp ls DEVICE_ID --storage 0x10001
terminal-usb mtp ls DEVICE_ID --storage 0x10001 --parent OBJECT_HANDLE
terminal-usb mtp get DEVICE_ID --handle OBJECT_HANDLE --file ./new-file
terminal-usb mtp put DEVICE_ID --storage 0x10001 --file ./input.txt
```

Select MTP/PTP on the target first. One alternate-zero still-image interface
(6/1/1) with bulk IN/OUT is required and uses an approved raw interface. Vendor
layouts/extensions, thumbnails, event subscriptions, delete and recursive copy
are not implemented. Storage/handle arrays are capped at 4096, listing returns at
most 256 objects and a truncation flag, and files are limited to 64 MiB. Device
errors abort the session; there is no hidden recovery/retry. Transactions have a
120-second deadline; best-effort CloseSession is limited to three seconds.
Downloads publish using a no-replace local hard link only after complete transfer.
Uploads create a UUID-prefixed new object and print its handle; interrupted
uploads can leave a partial object. Neither protocol offers a concurrent-write
snapshot guarantee. PTP cameras may reject upload or other optional operations.

### Android-mounted USB/SD storage folders

In USB settings, choose **Choose removable storage folder**, then select a folder
on a removable volume mounted by Android. Internal/emulated storage and arbitrary
providers are rejected. USB drives and SD cards are both removable volumes;
Android's folder API does not reliably identify their USB interface. Only the
picked `ExternalStorageProvider` tree is exposed, never its URI or a raw FD.

```sh
terminal-usb storage roots
terminal-usb storage ls ROOT_ID
terminal-usb storage ls ROOT_ID --parent DIRECTORY_HANDLE
terminal-usb storage get ROOT_ID --handle FILE_HANDLE --file ./new-file
terminal-usb storage put ROOT_ID --file ./input.txt
```

Picker permission and terminal-session capability are separate. The first read
or write needs a corresponding host approval; write includes read and creation
of new UUID-prefixed files, not overwrite/delete. Removal of folder access revokes
sessions and forgets the picker permission without deleting files. Unmount/eject
revokes removable-volume session grants; reattachment never silently restores
them. Media events conservatively revoke all removable-folder grants because a
trusted USB-to-volume identity is unavailable. Persisted folder selections can
survive app restart, but session grants cannot.

Limits: 16 roots, 4096 issued opaque document handles, 48 entries per listing with
`truncated` (no pagination), 64 MiB per known-size file, 16 KiB chunks, 125-second
operation/130-second client timeout, 30-second guest input inactivity. Read-only
volumes cannot receive write grants. Concurrent reads are allowed; writes reserve
the tree exclusively. Provider I/O is off the broker/UI lock. Revocation closes
the guest immediately and cancels the provider on a bounded worker pool; an Android
provider stuck inside Binder may not terminate promptly. A blocked operation is
not treated as restored or completed. Partial new remote files can remain after
failure. Downloads require a local filesystem supporting hard links.

This path deliberately reuses Android's mounted filesystem support. It does not
implement SCSI/BOT, a FAT parser, FUSE, partitioning, formatting or kernel mounts;
filesystems Android cannot mount remain unsupported.

## iPhone: Android usbmuxd port

The ARM64 APK builds upstream usbmuxd's mux protocol and asynchronous USB
transport together with libusb, libusbmuxd, libimobiledevice and Mbed TLS. Sources
and SHA-256 hashes are pinned in `tools/iphone/sources.lock`. It is a port of
upstream code, not an independent reimplementation of Apple's mux protocol.

```sh
terminal-usb list
terminal-usb iphone pair DEVICE_ID
terminal-usb iphone info DEVICE_ID
terminal-usb iphone unpair DEVICE_ID
```

Each action has its own host approval, in addition to Android's USB permission.
After `APPROVAL_REQUIRED`, approve in settings and retry. Unlock the iPhone and
accept its Trust prompt; if trust is pending or the operation expires, finish
the prompt and retry. `info` only returns DeviceName, ProductType,
ProductVersion, BuildVersion and DeviceClass; it never automatically pairs.
`unpair` asks iOS to remove this app's pairing and deletes the local record on
success. Revoking USB access stops work but does **not** erase an existing pairing.
Unpair before disconnecting if that is the intended effect. Pairing/unpairing is
not transactional: cancelling a reply cannot undo an already accepted iOS action.

The approved operation exclusively occupies that device; other devices and UI
revocation remain responsive. Host settings use interface `-1` for this explicit
device-level grant. The worker may select a USB configuration containing exactly
one alternate-zero Apple 255/254/2 interface with bulk IN/OUT. It prefers the
active configuration and never forcibly detaches kernel drivers. No automatic
Apple vendor mode switch, recovery/DFU, T2/Mac restore, tethering or video-mode
control is provided. If no usable mux interface is exposed, it fails explicitly.
A re-enumerated device always requires fresh approval, never reuse of an old FD.

Android hands its already-open USB FD to the trusted packaged worker using
SCM_RIGHTS after same-UID and one-use-token authentication. The worker uses
`LIBUSB_OPTION_NO_DEVICE_DISCOVERY` and `libusb_wrap_sys_device`; it does not scan
or open `/dev/bus/usb`. FDs are **never** passed to Rootless. There is no external
usbmuxd listener: libimobiledevice and the mux server use anonymous socketpairs
inside the worker. Raw `Connect`, `ReadPairRecord`, service ports, arbitrary info
keys and shell/tool arguments are not guest capabilities. Existing unmodified
`idevice*` clients cannot attach to this private worker; use the typed CLI.

Pairing records stay under Android's no-backup directory, outside Rootless binds.
The native store validates filenames, refuses symlinks, limits each record to
128 KiB, checks owner-only files and atomically replaces/fsyncs records. This
API confinement is **not** protection against malicious app-UID Native/PRoot
processes; the same-UID threat model above still applies. Strong credential
isolation would require a different OS identity, not just a private directory.

One operation runs in one service-owned subprocess. Pair/info/unpair native alarm: 25 seconds;
host watchdog: 27 seconds; CLI socket deadline: 30 seconds; worker JSON lines are bounded below 48 KiB;
device-supplied plist receive buffer: 1 MiB. An address-space limit is deliberately
not used because Bionic/Scudo reserves large virtual regions. Cancellation, CLI closure, detach, session
exit, service shutdown and host death close the operation's connection/process.
UI navigation/recreation alone does not cancel it. Process exit closes pending
URBs; upstream bulk/ZLP and RX logic is reused. Native diagnostics are not
forwarded to the guest; expected failures return bounded symbolic errors.

Only normal-mode Apple mobile VID/PID candidates (05ac:1290–12af) are exposed.
This does not promise compatibility with every iOS version, locked/managed phone,
USB configuration, adapter or Android OEM. Physical acceptance must include
pair/trust/deny, info over encrypted Lockdown, unpair, transfer packet boundaries,
disconnect/reconnect, cancellation, simultaneous requests and UI recreation.

### Standard AFC file access

After explicit pairing, Rootless Python clients can request:

```sh
terminal-usb iphone ls DEVICE_ID /DCIM
terminal-usb iphone get DEVICE_ID /DCIM/100APPLE/example.jpg ./example.jpg
terminal-usb iphone put DEVICE_ID ./example.txt
terminal-usb iphone put DEVICE_ID ./local-file --name import.txt
```

These use only `com.apple.afc`, whose root is the iOS-exposed media area, **not**
the full filesystem. Availability is controlled by iOS. No auto-pairing, AFC2,
House Arrest/app containers, backup, recursive copy or delete command is exposed.
Listing and downloading require separate action grants for the exact remote path;
uploads require a separate `/TerminalImports` grant. Grants last until normal
session/attachment revocation, not just one transfer. Paths must be absolute,
at most 1024 UTF-8 bytes, without traversal, empty components, controls or backslashes.
Observed symlinks and special files are rejected; AFC cannot provide race-free
path resolution against concurrent device-side changes.

Listing returns up to 256 names and a `truncated` flag, without pagination.
Files are limited to 64 MiB, streamed in at most 16 KiB chunks. Native/host/client
deadlines are 120/125/130 seconds; guest upload inactivity is bounded by the broker
socket deadline. Cancellation closes both worker and active guest socket to unblock
stalled streaming. Downloads use a private temporary file and publish only after
completion using a local no-replace hard link; choose a filesystem supporting hard
links (such as the Rootless home). Existing destinations are refused.

Uploads create `/TerminalImports/<host-UUID>-<ASCII-name>`; the destination is not
guest-selected. The worker checks for an existing target first, but AFC has no
atomic exclusive-create guarantee. A graceful write failure attempts to remove
only that operation's new file. Disconnect, cancellation or process death can leave
a partial remote file; its path is printed when upload begins. Chunk `accepted`
means buffered by the host, not durable remote storage. Only final `completed`
indicates successful close and size verification. Neither direction provides a
snapshot or checksum guarantee against concurrent same-size content changes.

Validation for this expansion: the canonical Android/runtime/image Gradle build
and tests passed, along with seven Python client tests and six Android worker/
broker instrumentation tests on an ARM64 Android 15 target. Worker self-tests
include AFC path/header bounds; JVM tests cover binary/empty downloads, rejected
streams and chunked upload acknowledgements. These are simulated protocol tests,
not successful transfers to an iPhone. The target exposes USB accessory, not Host.

The pinned AFC receiver is patched to reject invalid/oversized packet lengths and
truncated packets before exposing results. Physical AFC compatibility, throughput,
unplug and trust/lock behavior remain unverified without an iPhone/USB Host target.

### Build and licensing

`buildIphoneWorker` is a dependency of runtime `preBuild`. It uses the repository
NDK and existing native Clang on ARM64 Linux (NDK Clang on x86_64 Linux). First
build downloads the pinned source archives; later builds reuse verified archives
and incremental native objects. No autotools, alternate SDK or system package
installation is required. Non-ARM64 Android targets report a missing worker;
Native/Rootless/raw/CDC operation remains available as before.

Generated ELF and downloaded sources stay under `android-runtime/build`, never
in source control. All available upstream license files are included in APK
assets under `licenses/iphone`. See `tools/iphone/README.md` for reproduction,
GPLv3 distribution/source obligations and the exact upstream components.

## Validation

### Compatibility expansion, 2026-09-12

All seven work areas have a scoped implementation: device/protocol regression
mocks; cancellable concurrent raw I/O; PyUSB/PTY compatibility; apt-ADB and
standard MTP/PTP; removable-folder files; explicit configuration/alternate and
safe multi-port scopes; settings/profiles/live diagnostics. This is not a claim
that every USB class or physical peripheral works.

- Canonical `:android-app:assembleDebug :android-runtime:testDebugUnitTest
  :image:test` passed; `:android-app:assembleDebugAndroidTest` passed.
  Final Korean/English resource changes were followed by another APK build.
- USB JVM subset: **31 tests passed**, including denied-before-I/O, stalled-device
  concurrency, revoke-during-I/O, late result suppression, interface reservations,
  strict alternate/configuration and file request schemas.
- Python: **31 tests passed**, using real local sockets/PTYs, protocol mocks,
  official PyUSB **1.3.1** source and installed Debian ADB **34.0.4**. A private real
  ADB server connected to a mock localfilesystem transport and emitted CNXN;
  owned-server cleanup and parent-SIGKILL child termination were checked. No target phone was involved.
- The generated zipapp's `--help` and `run tests/usb/zipapp_probe.py` passed.
  Host tests do not prove Python execution inside the Android target's rootfs.
- Android 15 ARM64: final APK installed; **6 worker/broker instrumentation tests
  passed**, including real PRoot file bind/credentials, service socket denials,
  storage/configuration schema rejection and Activity recreation.
- Android MCP verified the target lacks USB Host. Replicant verified navigation,
  the unsupported-host state, the folder picker and rejection of an internal
  Documents folder (`SELECT_REMOVABLE_STORAGE_FOLDER`). No persisted removable
  root or file was created. Ambiguous Compose selectors were resolved once with
  observed coordinates. Context7 was consulted for Android APIs; official Android
  references/upstream sources supplied the precise protocol details. No external
  connected-app context was needed.

Reproduce the optional host compatibility suite without installing host packages:

```sh
# PYTHONPATH points at an extracted official PyUSB 1.3.1 source release.
PYTHONPATH=/path/to/pyusb-1.3.1 TERMINAL_TEST_ADB=1 \
  python3 -m unittest discover -s tests/usb -v
python3 android-runtime/build/generated/usbClientAssets/usb/terminal-usb.pyz \
  run tests/usb/zipapp_probe.py
```

Without PyUSB or the ADB opt-in, their tests explicitly skip. The initial sandboxed
socket test was denied by the host sandbox; the passing suite was rerun with
local-socket permission. No failed check is counted as a pass.

Unrun physical acceptance: each supported serial chip/port and line setting;
composite alternate/configuration switching; real ADB authorization/shell/file
transfer; MTP/PTP and mounted USB-drive roundtrips; iPhone trust/AFC; unplug/revoke
during transfer; throughput and sustained multi-device load. The available target
advertises USB accessory only. Android filesystem support and OEM permission/
kernel ownership constraints remain platform boundaries. Unsupported vendor
protocols, isochronous traffic, block/FUSE mounts, advanced MTP operations and
directory pagination are separate future scope, not hidden stubs in these paths.

### Earlier validation history

Unit tests cover bounds, control recipients, line coding, CDC layout rejection and
host-selected launch credentials. Python socket tests cover fragmented frames
and EOF. `UsbBrokerDeviceTest` exercises real Android sockets, invalid/expired
credentials, oversized frames and Activity recreation without USB hardware.

iPhone port validation on 2026-09-11: canonical Gradle build/tests passed;
Python client tests (4) passed; Android instrumentation tests (6) passed. The
installed APK's real Bionic worker passed private mux enumeration, pair-store
save/read/delete, invalid-path rejection, ephemeral RSA pairing key/certificate
generation, invalid-command rejection and SCM_RIGHTS handoff with rejection of
a non-USB descriptor. USB broker tests additionally rejected raw pair-record and
service-port requests and expired/forged credentials. Build checks confirmed
ARM64 PIE, Android system-only dynamic dependencies, 16-KiB LOAD alignment and
packaged licenses; a corresponding-source archive was generated. These are not
proof of physical USB transfers or an iPhone TLS/Trust handshake. During testing,
an incompatible RLIMIT_AS cap failed under Bionic/Scudo; it was removed and
replaced by a device-plist allocation bound before the passing run.

Android MCP supplied installation, native/instrumentation execution and the
USB-feature check. Context7 confirmed Android/libusb FD wrapping; repository and
upstream source inspection supplied the version-specific implementation details.
Replicant checked USB settings navigation (ambiguous selectors were resolved
with exact text/observed coordinates). No external connected-app state was
needed. The available Android 15 redroid still has no USB Host or attached iPhone;
the physical iPhone acceptance checks above remain unrun.

Validation on 2026-09-11: the canonical Gradle build/tests, Python socket tests
(2) and Android instrumentation tests (3) passed. The Android tests also checked
the real PRoot client bind and credential injection. Replicant verified settings
navigation and the unsupported-host message. The available Android 15 redroid
target advertises USB accessory support but no USB Host; physical transfers and
the system USB permission dialog were not exercised. Python was absent from its
provisioned Rootless images, so the Python CLI was tested on the host, not end to
end inside that device's Rootless environment.

Physical acceptance requires a Host-capable phone and CDC ACM target: grant/deny
both permissions, verify loopback bytes, unplug/reconnect during I/O, revoke
during I/O, test competing sessions, recreate/detach the UI while transferring,
and verify only the owning session exit closes its connection. Raw control,
bulk and interrupt transfers need a documented test device. These checks cannot
be inferred from compilation or a virtual Android device without USB Host.
