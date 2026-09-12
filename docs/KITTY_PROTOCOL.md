# Kitty keyboard, window and remote-control compatibility

## Ownership and enablement

Keyboard negotiation is always available on the shared terminal renderer. No
setting or environment spoofing is needed for an application that negotiates it.
The runtime service owns remote control and sessions; an Activity is only a
renderer and receives explicit focus requests.

In Settings → Terminal, **Kitty 원격 제어** defaults to off. Turning it on grants
remote control to sessions subsequently created from the Android UI. Programs in
such a session can list, create, rename, focus, write to, resize and terminate
sessions in the **same selected runtime**. Native, Debian, Ubuntu, Arch and AVF
are separate scopes. Turning it off revokes existing grants immediately; turning
it back on does not restore them. Remote-created children have no grant. This
is a PTY-wide grant, not authentication of individual executables in that PTY.

The independent **kitty 그래픽 호환성** setting exports a persistent, positive
`KITTY_WINDOW_ID` to new sessions for clients that use this detection heuristic.
Remote-enabled sessions and remote-created windows also receive an ID. IDs are
allocated by the host and remain unchanged across detach/attach and UI recreation.
Neither setting changes the selected `TERM` or exports `TERM_PROGRAM=kitty`.
Existing shell environments are not rewritten. Service death does not restore
remote grants; lost Native/Rootless PTYs are not advertised as restored sessions.

## Keyboard

The emulator supports query (`CSI ? u`), push (`CSI > flags u`), pop
(`CSI < count u`), and set/add/remove (`CSI = flags ; mode u`) with independent
main/alternate-screen stacks, up to 32 entries. RIS resets both stacks.
All five progressive enhancements are encoded: disambiguation, event types,
alternate keys, all-key reporting and associated text. Hardware press/repeat/
release, toolbar chords and IME committed text use the negotiated mode. IME text
without a physical key identity uses key number zero with associated codepoints.
Unnegotiated input retains the existing legacy encoding, and ordinary recovery
keys remain usable according to the protocol. Switching sessions clears tracked
physical presses so a release cannot leak into another session.

Android can only report keys, modifiers and layout information supplied by its
input system. An IME composition is not a stream of physical keyboard events.
Multiline paste continues to use the existing paste path.

## Window model and remote commands

One application session/tab is one kitty window and one kitty tab. They have the
same numeric ID. There is one logical OS window with a stack layout; focus selects
an existing Android tab and never creates an Activity or takes ownership of a PTY.

Requests use the official PTY framing:
`ESC P @kitty-cmd {"cmd":"ls","version":[0,46,0],"payload":{}} ESC \\`.
Replies use the same framing and an `ok`, optional `data`, or `error` JSON object.
`no_response` suppresses a reply. The version array must have major version zero.
The source is the host-known PTY; a supplied `kitty_window_id` must agree with it.
No socket, TCP listener, password or encrypted remote transport is exposed.

| Commands | Behavior |
| --- | --- |
| `ls` | JSON string containing logical OS window, tabs and windows, restricted to the source runtime. `self` lists the source only. |
| `launch`, `new-window` | Create a same-runtime session; empty args start a shell. `window` and `tab` map to Android tabs. Supports cwd, argv, bounded env, title and keep-focus. |
| `focus-window`, `focus-tab` | Select the first matching Android tab; focus is retained for a later UI connection. |
| `set-window-title`, `set-tab-title` | Rename matching sessions. |
| `close-window`, `close-tab` | Explicitly terminate and remove matching sessions, including their live processes. |
| `send-text` | Write UTF-8 `text:` or standard `base64:` bytes; explicit bracketed-paste enable/disable. |
| `send-key` | Send named chords using each target's observed keyboard mode, including negotiated release reports. Supports text, arrows/navigation, Enter/Tab/Backspace/Escape, F1–F12 and modifiers. |
| `resize-window` | Apply a horizontal/vertical cell increment to the PTY. Android viewport layout can subsequently resize it again. |

Launch titles are initial labels and do not pin the Android tab: an OSC title
can replace them. A nonblank `tab_title` explicitly pins the tab title, as do
the rename commands. UI tab resolution is explicit title, application OSC
title, then initial/profile label.

Matching supports numeric `id:`, exact `title:`, `state:focused`/`state:active`,
`all`, `self`, `match_tab`, and excluding the active session. With no selector,
mutation commands target the source session. `ls` defaults to the permitted
runtime's windows. Title matching is literal, not a regular-expression engine.
The listing does not expose full environment variables, host paths outside the
session descriptor or foreground process introspection; PID is reported as zero.

For an installed compatible `kitten` client in a newly permitted Rootless session:

```sh
kitten @ ls
kitten @ launch --type=tab --title='Build' /bin/bash
kitten @ set-window-title --match id:42 'Build logs'
kitten @ send-text --match id:42 'echo hello\n'
kitten @ close-window --match id:42
```

Replace `42` with an ID returned by `ls` or `launch`. Closing is destructive.
The Android APK supplies the protocol endpoint, not the desktop kitty executable.

## Boundaries and unsupported desktop features

Requests are bounded to 64 KiB with JSON nesting at most 16. There are 64 pending
remote requests globally; overload is dropped instead of blocking PTY output.
Input is bounded to 32 KiB per command, titles to 64 codepoints/256 UTF-8 bytes,
launch arguments to 64 entries/4096 encoded bytes, and launch environment to 64
entries/16 KiB. Remote launch stops at 64 existing windows in its runtime.
Internal `DROID_*`, `KITTY_*`, `PROOT_*` and loader-injection variables cannot be
supplied by remote launch. Arguments are passed as argv, never interpolated into
a shell command. The internal `DROID_SESSION_ARGV` length-prefixed UTF-8 envelope
is consumed by Native, Rootless or the updated AVF guest and removed from the
child environment. AVF also enforces its existing session-wire bounds.

Replay output is never dispatched as a command. An incomplete, malformed or
nested control string cannot cause a command to be executed. The bounded command
worker is separate from PTY output draining and snapshot locks. Revocation is
checked again before mutations; a mutation already underway may finish.

Desktop split layouts, OS window positioning/resizing, actions/plugins/watchers,
config loading, font/color control, process introspection/get-text, broadcasting
sessions, password/encrypted/socket transport and async/stream commands are not
implemented. Unsupported commands return errors; desktop launch options that
would require a new execution or authority path are rejected. This is a defined
Android compatibility subset, not the entire desktop kitty application.

Native and Rootless continue working without AVF. AVF uses the same host dispatcher
and the updated guest argv decoder, but live AVF remote control requires a
privileged target and its attached guest output stream; it has not been verified
on an AVF device. Ordinary Android UI detach does not intentionally kill sessions.

## Validation

Unit tests cover keyboard stacks and encoding, fragmented/UTF-8 framing, malformed
and oversized controls, default-deny, source identity, runtime isolation, launch
arguments, remote child grants and typed window/input operations. Android 15 ARM64
instrumentation covers hardware release, toolbar Ctrl+C, Korean IME, return to
legacy mode, and real Native/Rootless PTY remote requests while detached, replay,
window lifecycle and permission revocation. The Rust guest argv decoder has a
matching UTF-8/truncation test. See the accompanying implementation report for
commands and final validation results.

References: [keyboard protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/),
[remote protocol](https://sw.kovidgoyal.net/kitty/rc_protocol/),
[remote control](https://sw.kovidgoyal.net/kitty/remote-control/).

### 2026-09-09 verification record

- Canonical `:android-app:assembleDebug :android-runtime:testDebugUnitTest :image:test`
  and affected app/emulator/view unit tests passed. The optional external archive
  fixture test remains skipped; no build failure is hidden by that skip.
- Rust guest tests: 15 passed using the existing temporary toolchain/cache.
- Android MCP: Native/Rootless remote lifecycle/deny/revoke/replay instrumentation
  passed. Keyboard, hotkey, multiline-paste and graphics regressions subsequently
  passed 11/11 on Android 15 ARM64.
- Replicant: settings navigation and both Kitty explanations were inspected.
  An ambiguous selector was replaced with the observed element coordinates.
  Automatic approval review rejected enabling remote control through the live UI
  because that would grant control over same-runtime sessions. The toggle was not
  changed; live UI focus validation after that step remains unrun. Earlier scoped
  instrumentation restored its prior settings and removed its test sessions.
- Context7 and official kitty references supplied protocol details. No connected
  external issue/application required codex_apps. No suitable privileged AVF target
  was available; the AVF integration checklist was not run.
