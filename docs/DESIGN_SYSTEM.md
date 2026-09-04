# Terminal Grid Design System

## Direction

Droid Terminal uses an application-owned system named **Terminal Grid**. Low-luminance neutral graphite layers keep long terminal sessions comfortable, while mint is reserved for focus, selection, active state, progress, and the primary command. The terminal canvas itself is true black.

## Foundations

- Color: achromatic graphite layers and text, with mint as the sole product accent. Validation uses contrast and border weight instead of introducing another decorative hue.
- Spacing: a 4 dp grid with 4, 8, 12, 16, 20, 24, and 32 dp tokens.
- Shape: 8 dp for dense controls, 12 dp for actions/fields/rows, 16 dp for grouped surfaces, and pill geometry only for compact values and switches.
- Touch: interactive targets are at least 48 dp, except fixed extra-key cells whose complete row remains touch-sized.
- Type: system sans-serif for Android UI. Monospace is limited to the terminal renderer, diagnostic/crash payloads, and terminal extra keys where fixed cells carry meaning.
- Iconography: Lucide 24 px outline icons use the source set's 2 px round stroke. Product branding may use an application-owned mark, but navigation and actions must not use hand-drawn one-off glyphs.
- Layering: `void`, `base`, and three explicit layers replace Material surface/elevation roles.

Android resource tokens live in `android-app/src/main/res/values/colors.xml`, `dimens.xml`, and `styles.xml`. `SignalAppBar`, `GridFieldView`, panel backgrounds, row states, tab states, and choice dialogs are owned by the application.

Material Components is included only for Material 3 motion primitives: hierarchical screen navigation uses `MaterialSharedAxis`, in-place tab and settings updates use `MaterialFadeThrough`, and choice dialogs use `MaterialFade`. It does not supply visible application components or override Terminal Grid tokens.

Lucide source icons are converted to Android VectorDrawable paths without changing their 24 px geometry or 2 px round-stroke rules. Redistribution notices are packaged at `android-app/src/main/assets/licenses/lucide-LICENSE.txt`.

## Screen rules

### Terminal

The console has exactly three vertical regions: session tabs and actions, the native terminal renderer, and two fixed rows of extra keys. Tabs, add, and settings actions share one structural layer. A tab owns only attachment state; closing it explicitly asks the runtime supervisor to terminate the matching session.

The default guest session starts in `/home/user`. The shell prompt represents that home directory as `~`; the runtime protocol keeps the real absolute path for process creation and storage isolation.

### Initial setup

Setup explains the default native path first and AVF as an optional advanced path. One filled action starts the terminal; one outlined action selects a Debian image. Capability and platform constraints remain visible before the user commits.

### Settings

The overview uses rounded grouped surfaces with a leading category symbol, title, summary, and navigation affordance. Detail-page headings such as 글자, 리소스, and 실행 only create a list section; they never receive a shared card background or enclosing outline. Detail pages use Terminal Grid controls that match the value:

- free numeric or path input: `GridFieldView` with inline signal/error validation;
- boolean state: a platform `SwitchCompat` with neutral track and mint active state;
- bounded choice: a value chip in the row and a single-selection radio dialog;
- navigation or system action: preference row with trailing chevron.

Settings that affect process creation state when they take effect. Terminal appearance is applied on return; VM resources apply on the next VM start; shell defaults apply to new sessions.

### Diagnostics and crash reports

Operational information uses selectable monospace text inside a grouped surface. Refresh is an app-bar action. Crash reports keep “copy” as the primary action and “dismiss” as a text action so diagnostic data is not discarded accidentally.

## System bars and IME

Every visible activity is edge-to-edge but must reserve system-bar space. `SignalAppBar` owns the status-bar top inset. `TerminalActivity` owns horizontal and navigation-bar insets, and input screens additionally apply the IME bottom inset. The console applies the same contract to its tab chrome, so neither tabs nor settings bars may render under device status icons.

## Lifecycle boundary

The design system never changes runtime ownership. Activities render and issue commands through AIDL; the runtime process owns sessions and VM state. Visual tab removal is not used as a substitute for runtime termination acknowledgement.
