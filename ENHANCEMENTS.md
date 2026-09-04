# Termux 0.118.3 enhanced build

This project keeps Termux's package name and bootstrap compatibility while adding:

- a Material 3/Material You interface with a top app bar and horizontally scrollable session tabs;
- a close action on every tab that terminates and removes its terminal session (tap to switch,
  long-press to rename);
- inline Android IME composing previews for Hangul and other composing input methods, based on
  [termux-app PR #5242](https://github.com/termux/termux-app/pull/5242);
- an ordered, multi-font terminal fallback family with arbitrary filenames;
- an option to force glyphs to the terminal's fixed-width cell grid;
- new-tab and settings actions on the same row as the session tabs;
- an optional soft-keyboard action on the session-tab row.

## Font family

Put font files in `~/.termux/fonts/`, then run
`termux-reload-settings`. There is no required filename. When no family is configured, all font
files directly in that directory are validated and loaded in case-insensitive filename order; the
filename and extension are not used to decide whether a file is a font.

To choose the fallback order, add a comma-separated list to `~/.termux/termux.properties`:

```properties
font-family = JetBrains Mono.ttf, Noto Sans CJK KR.otf, symbols/custom-symbols.ttf
```

Relative entries are resolved under `~/.termux/fonts/`. Absolute paths and paths beginning with
`~/` are also accepted, and an explicitly listed file does not need a particular extension. The
first font containing a glyph is used; Android's monospace font remains the final fallback. Bold
and italic are rendering styles of the selected font, not separate family entries.

The old `~/.termux/font.ttf` path is used only as a compatibility fallback when no new family font
could be loaded.

Open **Settings → Termux → Terminal View → Fonts** to import multiple font files, select automatic
folder discovery or a manual fallback order, remove fonts from the active family, and control
fixed-width glyph fitting. The imported file keeps its own safe filename under
`~/.termux/fonts/`; it never needs to be renamed to `font.ttf`.

Fixed-width fitting is enabled by default to preserve terminal-grid alignment. When disabled,
glyphs use their natural widths while their starting positions, backgrounds, selections, and cursor
remain on the terminal cell grid.

## Keyboard options

Open **Settings → Termux → Terminal I/O** to disable IME composing preview or to show the dedicated
soft-keyboard tab-row action. IME composing preview is enabled by default; the action is hidden by
default.

## Portable native packaging

The four bootstrap archives are packaged as ABI-selected assets and verified against the SHA-256
values in `app/build.gradle`. The four small `libtermux.so` pty bridge files come from the signed
official Termux v0.118.3 universal APK. This lets the project build on ARM64 or x86_64 hosts without
changing Termux's native behavior or requiring a host-compatible Android NDK.
