# Terminal graphics: SIXEL and kitty

## Decision and scope

The common `terminal-emulator` owns per-screen image, placement and animation
state. `terminal-view` renders it and schedules redraws for visible animations.
The runtime service or guest session daemon continues to own the PTY. Attaching,
rotating or destroying a view does not terminate a process.

Kitty graphics actions are implemented across direct transfer, local media,
placements, Unicode placeholders and animation. Keyboard negotiation and the
Android window/remote-control subset are described in [KITTY_PROTOCOL.md](KITTY_PROTOCOL.md).
The default remains `TERM=xterm-256color`; `TERM_PROGRAM=kitty` is not exported.
The optional graphics compatibility setting gives new sessions a host-allocated
`KITTY_WINDOW_ID`; remote-enabled sessions also receive one. SIXEL remains available.

## Supported operations

- Transfer/query: `a=t/T/q`, RGB/RGBA/PNG (`f=24/32/100`), zlib (`o=z`), direct
  base64 chunks (`t=d`, `m=1/0`), quiet replies (`q`), image IDs and image-number
  allocation/newest-number lookup (`i/I`). The optional transient hint `N` is
  accepted; the bounded in-memory store does not need a separate disk policy.
- Placement: `a=p`, placement IDs, cell sizing and aspect-preserving letterboxing,
  source cropping (`x/y/w/h`), cell-internal pixel offsets (`X/Y`), cursor policy,
  and signed z order. Negative images render below text; those below
  -1,073,741,824 also render below explicit cell backgrounds. Equal-z images
  use image-ID ordering.
- Relative placement: `P/Q/H/V`, moving-parent updates, placeholder-derived
  anchors, cycle/missing-parent/depth errors and cascading child removal.
- Unicode placement: virtual `U=1` prototypes and U+10EEEE cells, the protocol's
  row/column/high-byte diacritics, foreground image IDs, SGR 58 underline-color
  placement IDs, omitted-value inheritance, and metadata preservation through
  text copying/reflow. Metadata glyphs are hidden by the renderer.
- Deletion: all visible placements, image ID/number, cursor/cell, cell+z,
  ID range, row, column, z and animation frame selectors; uppercase selectors
  release image data when no placements still reference it. Ordinary text
  erasure does not delete kitty placements; screen clearing does. Virtual
  placement cells follow ordinary text erasure.
- Animation: `a=f` append/edit frames, background/base-frame composition,
  replacement or alpha blending, `a=a` client selection and terminal-driven
  stop/loading/run states, gaps, finite/infinite loops, and `a=c` rectangular
  frame composition. Deletion and animation control do not acknowledge success;
  frame-transfer acknowledgements include the frame number.
- SIXEL: raster dimensions, RGB/HLS registers, repeats, carriage return and
  six-pixel row advance, transparent/background fill and DECSDM cursor policy.
  Primary DA advertises SIXEL attribute 4. Pixels are square; legacy SIXEL
  aspect-ratio scaling and mode 8452 are not implemented.

## Local media and security

Local media is resolved by the PTY owner **before** transcript recording or UI
transport. The backend replaces `t=f/t/s` with bounded `t=d` chunks. Thus replay
uses captured pixels even after a temporary file or shared-memory object has
been unlinked. UI code never opens paths from terminal output.

| Runtime | Local file / temporary file | POSIX shared memory |
| --- | --- | --- |
| Native | Within its host-selected Native root, including home and TMPDIR | `ENOTSUP` on Android/Bionic |
| Rootless | Selected rootfs, home, workspace and explicitly bound shared storage | Guest `/dev/shm`, explicitly bound to that distribution's private temporary directory |
| AVF | Resolved inside `droid-sessiond` in the guest | Guest `/dev/shm` |

`S/O` select a bounded size/offset. Rootless symlinks use guest-absolute path
semantics, so `/root`, `/workspace` and `/dev/shm` never resolve against the
Android root. Sensitive `/proc`, `/sys` and device paths are refused. The final
reader pins directories using `openat`, `O_PATH` and `O_NOFOLLOW`, verifies a
regular file and bounds the read. FIFO/device reads cannot block the output
pump. Temporary deletion requires an allowed temporary directory and a
`tty-graphics-protocol` path marker; shared-memory names must remain inside
the selected shared-memory root. Unlink uses the pinned parent and an inode
check. No host path or descriptor crosses the AVF boundary.

AVF converts local media cooperatively in the session loop: one turn reads at
most seven 3 KiB blocks and emits <32 KiB of ordinary direct-transfer APCs. The
existing bounded output queue owns those chunks; no per-image encoded backlog
or worker pool is added. m=1 chunks remain incomplete at the emulator. Only a
successful final read/cleanup emits m=0. Read failure sends APC + CAN to cancel
that transfer, preserving committed images and the failed source. Resize cancels
the reader and staged raw remainder before its response, with an OutputGap that
also clears incomplete multipart transfers on both screens. Individual regular-file
operations are synchronous and have no new hard latency guarantee.

Files on an SSH server must use direct transfer unless the client and PTY owner
share the same filesystem. No additional Android permission or capability grant
is implied by an image command. Native media access deliberately excludes the
rest of app-private storage; use direct transfer for other locations.

## Bounds and restoration

An image is at most 2048 pixels per dimension and 1,048,576 pixels total. Each
screen stores at most 64 images, 128 placements, 128 frames per image and
8,388,608 decoded pixels across all frames (32 MiB ARGB). Image eviction removes
its placements; frame growth beyond quota returns `ENOSPC`. The renderer caches
only visible current-frame bitmaps, at most another screen pixel budget. Relative
chains are limited to 32 parent links and placements to 4096 cells per dimension.

Each screen holds one incomplete kitty transfer, at most 6 MiB of decoded
transfer bytes; decompression has the same cap. Direct base64 chunks are at most
4096 characters. PNG dimensions are checked before allocation. SIXEL input is
capped at 6 MiB characters and expansion at 16,777,216 pixel visits per pass.
Oversized APC/DCS strings are drained through ST; CAN/SUB abort them. Animation
timing uses monotonic time and bounded work after a long detach. These limits
bound resource use; large accepted images can still delay UI decoding.

Native and Rootless retain up to 8 MiB of output at safe terminal/UTF-8/kitty
transfer boundaries. Replay precedes subsequent live output and is emitted in
at most 32 KiB frames. Historical protocol replies are suppressed. Retained
complete transfers reconstruct images, placements and animation commands; this
is a bounded transcript, not a persistent image archive. Evicted transfers and
an oversized incomplete transfer cannot be reconstructed. AVF keeps its existing
session subscription semantics; this change adds media conversion, not a new
persistent guest transcript store. Actual runtime/PTY death means session loss.

Full-screen scrolling retains placements in bounded scrollback. Images wholly
inside a partial scroll region move and clip; intersecting placements stay
fixed. Alternate-screen images are separate and cleared on entry; returning
restores the main screen. Resize adjusts ordinary cell anchors and scales with
font cells. Ordinary image anchors do not follow individual text characters
through column reflow; Unicode placements do. tmux must enable its own
passthrough to deliver graphics APCs; the terminal does not interpret tmux's
private wrapper as a new graphics command.

## Codex CLI pets: capability detection compatibility

Codex CLI 0.153.4 was reproduced in a separate PTY with the emulator's primary
DA reply (including SIXEL attribute 4). With `TERM=xterm-256color`, `/pet`
reported that no supported image protocol was available. Providing nonzero PTY
pixel dimensions did not change that result. No kitty image query was emitted
in the observed startup or `/pet` path. With the same replies and
`TERM=xterm-sixel`, the pet picker opened and emitted a real SIXEL frame.
This is observed behavior of that CLI version, not a claim that every CLI
version discovers terminal capabilities the same way.

A second isolated PTY probe of Codex CLI 0.153.4 with `KITTY_WINDOW_ID=1` and
`TERM=xterm-256color` opened the pet picker and emitted chunked direct PNG kitty
transfers (`a=T,t=d,f=100`). The app now offers the **kitty 그래픽 호환성** setting
for this detection path: enable it, create a new Rootless session, and launch
Codex there. This observation verifies the CLI's emitted protocol; it is not an
end-to-end Codex-on-Android validation. It does not require remote-control authority.

Alternatively, for this version, exit the current Codex CLI and start it from the Rootless
shell with an invocation-scoped terminal profile:

```sh
TERM=xterm-sixel codex
```

Then run `/pet` or `/pets`. If using tmux, test directly in the Rootless shell
outside tmux first. Do not set `TERM_PROGRAM=kitty` globally: application identity and environment
heuristics are separate from graphics protocol support. The command above
selects a SIXEL profile for that Codex process without changing the shell's
default environment. Processes launched by Codex inherit that value; ordinary
ncurses applications may need a matching `xterm-sixel` terminfo entry, which
was not present on the diagnostic host. For those commands, use the existing
`TERM=xterm-256color` profile. This workaround is not installed globally or
added to shell startup files.

The emitted pet frame (`DCS 9;1;0q`, 69 by 75 declared pixels, 256-color palette)
was replayed through a separate Rootless Debian session in the Android app to
validate the actual renderer. The previous static-image tests alone did not
validate Codex's environment-based protocol selection. CLI configuration and
pet selection in the user's Rootless installation are not modified by this
check. The graphics implementation now supports animation; compatibility with every
third-party application still requires its own runtime check.

Official [Codex command documentation](https://learn.chatgpt.com/docs/developer-commands?surface=cli)
documents `/pet` and `/pets`; the terminal-profile workaround above is based on
the local CLI experiment, not on an official compatibility guarantee.

## Smoke commands (Rootless shell)

A red SIXEL rectangle:

```sh
printf '\033P0;1q#1;2;100;0;0#1!120~-!120~-!120~\033\\'
```

A red/green terminal-driven animation (stop with `a=a,i=1,s=1`):

```sh
printf '\033_Ga=T,f=24,s=1,v=1,c=10,r=3,i=1,C=1,q=2;/wAA\033\\'
printf '\033_Ga=f,f=24,s=1,v=1,i=1,z=500,q=2;AP8A\033\\'
printf '\033_Ga=a,i=1,r=1,z=500,s=3,v=1\033\\'
```

The commands suppress acknowledgements because an interactive shell is not a
protocol client. Image applications normally read their own replies. Direct
transfer also works over SSH. File and shared-memory clients can use the runtime
paths in the table above; environment-based auto-detection is application-specific.

## Validation

Unit tests cover wire fragmentation, decoding, limits, queries, deletion,
placement/reflow, relative chains, animation timing/composition, media conversion,
namespace mapping, and replay. Android instrumentation additionally checks PNG
and Canvas pixels, negative z/background layering, clipping, placeholders,
animation cache updates, JNI path safety, Native temporary-file transfer and
Rootless file/shared-memory transfer with detach/attach reconstruction.
Rootless instrumentation requires an already provisioned rootfs and skips when
it is absent; it never installs a distribution. Guest Rust tests exercise media
conversion and actual regular-file/FIFO/symlink/temp-file behavior on the host.

The earlier static-image and Codex `/pet` checks were recorded on 2026-09-08.
Expanded protocol validation is recorded below.
AVF device integration still requires a privileged ARM64/AOSP target; host tests
do not certify its vsock/runtime path. Third-party application detection,
maximum-image performance and exact reflow across all historical sizes are not
covered by these tests.

References: [kitty graphics protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/),
[xterm SIXEL controls](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h3-Sixel-Graphics),
[Android bounded bitmap decoding](https://developer.android.com/topic/performance/graphics/load-bitmap).

### Recorded expanded validation (2026-09-09)

- Canonical `./gradlew :android-app:assembleDebug :android-runtime:testDebugUnitTest
  :image:test --console=plain`: passed. Runtime: 46 passed, 1 skipped (the
  pre-existing optional archive test requires `ROOTLESS_TEST_ARCHIVE`); image: 4 passed.
- `./gradlew :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest
  :android-app:assembleDebugAndroidTest --console=plain`: passed; emulator 198
  and view 8 tests passed. No clean build, Gradle/SDK change or system package install.
- `cargo test --offline --manifest-path guest/droid-sessiond/Cargo.toml`: 14
  passed, including 8 graphics transport tests. The host had no Rust installation;
  an official, checksum-verified Rust 1.85.0 toolchain and isolated Cargo cache in
  `/tmp` supplied this check. Existing nix 0.29 read-signature, Debug derivation
  and test-borrow errors were corrected to make the guest test target build.
- Android 15 / ARM64: `KittyProtocolDeviceTest` and `TerminalGraphicsDeviceTest`,
  8 passed with the final APK. Rootless created shared-memory contents through
  the guest shell, then verified file/shared-memory deletion and image replay.
  Native temporary-file deletion/replay, JNI path restrictions, PNG, Canvas
  layers, placeholders and animation changes also passed.
- Replicant UI smoke: a separate Debian shell displayed an animation changing
  from red to green with no intervening PTY output. The test used its own
  temporary script and session; the script and shell were removed afterward.
- android MCP supplied install/instrumentation and runtime evidence. Replicant
  supplied UI input/navigation and screenshots. Context7 supplied kitty
  placeholder rules; its nix result was not version-matched, so nix 0.29's
  locally cached source supplied the exact API signatures. No connected-app
  context was needed.
- Fixed validation failures: SGR colon-color parsing initially affected an
  existing regression test; narrowing the color-space-slot parsing fixed it.
  Android media reads initially failed on directory permissions and Android's
  `/data/user/0` alias; O_PATH traversal and canonical host-selected roots fixed
  both. An ambiguous UI selector used observed coordinates; an Android shell
  heredoc temporary-directory failure used a base64-written temporary script.
- Not run: privileged AVF device/vsock acceptance, exhaustive third-party
  application compatibility and maximum-image performance. Native POSIX shared
  memory remains a platform limitation; direct/file transfer is available.

## Synchronized presentation and Codex pet flicker

Codex CLI 0.153.4 was captured in an isolated PTY while updating its pet. All
52 PNG transmissions and their surrounding image deletions were inside DEC mode
2026 synchronized-update boundaries (139 begin/end pairs in the whole capture).
The previous emulator ignored these boundaries and notified the view after each
4 KiB input read, allowing a deleted image to become visible as an empty frame
before all replacement chunks arrived.

The emulator now accepts `CSI ? 2026 h/l` and answers `CSI ? 2026 $ p` with set/
reset status. Parsing, protocol responses and PTY input continue normally. The
renderer retains one completed Android Picture display list and replays it while
an update is active, including on cursor/IME/system invalidations. Only an end
marker publishes the new frame, or a fixed one-second deadline releases a stalled
update. Repeated begin markers cannot extend that deadline. Reset/resize release
the barrier; replay never enters it. Cached frames are isolated by emulator and
viewport, and released on view detach/session changes. No deletion heuristic or
extra permission is involved: a delete outside synchronization remains immediate.

Validation: emulator mode/query/deadline/replay tests and the canonical Gradle
build passed. Android 15 ARM64 pixel tests force draws after delete, intermediate
chunks, final chunk and end marker; the old image remains until the end marker
and is then replaced without an empty intermediate frame. Standalone delete,
timeout, cross-session isolation, existing graphics and keyboard regressions
passed 11/11. Android MCP ran instrumentation; Replicant exercised the visible
Native terminal. Context7 supplied synchronized-update guidance, supplemented by
[the mode 2026 specification](https://github.com/contour-terminal/vt-extensions/blob/master/synchronized-output.md)
and [Android Picture documentation](https://developer.android.com/reference/android/graphics/Picture).
The captured Codex stream and Android rendering were verified separately; this
is not a claim of a measured flicker rate for Codex running on the user's device.

## Scrollback during continuous graphics output

Output invalidation used to reset `TerminalView.mTopRow` to zero unless text was
selected. Thus each pet image refresh pulled a reader back to the live cursor,
regardless of synchronized presentation. The view now follows output only when
already at the bottom. A scrolled viewport is anchored to history content using
a monotonic output-driven transcript scroll counter, including ring-buffer
eviction. Graphics/title updates and horizontal-region edits do not move this
anchor. Saved tab anchors account for output received while the tab is hidden.
Inertial scrolling applies gesture deltas rather than stale absolute positions.
Local keyboard/IME/paste input explicitly resumes following the prompt.

When the user changes the viewport during a synchronized update, the renderer
renders the new viewport rather than returning a blank frame for a mismatched
cached picture. A stable viewport still receives completed synchronized frames.
Android instrumentation covers repeated pet delete/transmit updates, growing and
full history, cleared history, background tabs and input resumption. This is a
shared scrollback policy, not a Codex-specific image filter.

Validation also includes a hardware-accelerated Activity/PixelCopy regression:
scroll during a synchronized update, repeatedly invalidate, then finish the update;
the history pixels remain identical. On the connected Android 15 ARM64 target,
a Native shell refreshing a Kitty image every 100 ms kept its scrolled row stable.
Some live screenshots intermittently showed a blank terminal despite that stable
row; the cause is unresolved and the deterministic hardware regression did not
reproduce it. This is not evidence that all visual flicker is eliminated, nor a
validation of Codex running on the user's device.

## Complete UI-process restoration

The runtime now owns full terminal state for each session, including both screens,
retained graphics/animation frames, parent/virtual placements and incomplete
transfers. UI reconnects install a checkpoint before following ordered live output
rather than depending on the last 8 MiB of raw bytes. Animation commands advance
elapsed time even when no renderer is present. The synchronized-output deadline
also survives a checkpoint; a new renderer waits for completion or that remaining
deadline before presenting a partial batch. Actual runtime/VM death and images
already evicted by existing quotas remain outside this guarantee. See
[TERMINAL_TUI.md](TERMINAL_TUI.md) for lifecycle evidence and transfer boundaries.
