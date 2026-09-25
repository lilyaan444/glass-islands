# Glass Islands

A JetBrains Rider plugin for macOS that gives the IDE a translucent, Apple-like look built from real AppKit
materials, rendered by the macOS compositor. No screenshots, no painted blur, no theme swap.

- **Window and Islands panes**: frosted `NSVisualEffectView` (material *Under Window Background*, blending
  *behind window*), tinted, with soft shadows and a specular rim. The editor stays the most opaque layer.
- **Buttons and tabs**: selected and hovered toolbar buttons, tool window buttons and editor tabs become
  Liquid Glass pills (`NSGlassEffectView` in an `NSGlassEffectContainerView`, macOS 26+; frosted pills before).
- **Popups and menus** (context menus, Quick Switch, Search Everywhere…) wear the *Menu* material, rounded like the
  popup itself; **floating tool windows** the *Popover* material; **detached editor windows** get the same islands as
  the main window. Dialogs stay opaque, as on macOS.
- Follows the Rider theme (dark or light) and the macOS accessibility settings:
  *Reduce Transparency* turns it off, *Increase Contrast* makes every layer more opaque and outlined.
  Presentation Mode stays opaque.

Requires Rider 2026.2 (build 262.10315+) with an Islands theme, on macOS 12+ (Apple Silicon or Intel).

## Build

```bash
./gradlew buildPlugin
```

The installable archive is `build/distributions/glass-islands-<version>.zip`. It contains the plugin jar and
one universal (`arm64` + `x86_64`) `native/libliquidglass.dylib`, signed ad hoc. For a public release, sign it
with a Developer ID: `RLG_SIGN_IDENTITY="Developer ID Application: …" ./gradlew buildPlugin`.

Other tasks:

| Task | Purpose |
| --- | --- |
| `./gradlew test` | Unit tests, including the real dylib through the Foreign Function & Memory API |
| `./gradlew verifyPlugin` | JetBrains Plugin Verifier against the local Rider (compatibility, internal API) |
| `./gradlew runIde` | Sandboxed Rider on `sample/HelloGlass` |
| `./gradlew prepareSandbox_runIde` | Rebuilds the plugin in the running sandbox: Rider unloads and reloads it live |
| `native/test/run-harness.sh` | The native bridge alone, in a plain Swing window on the JetBrains Runtime |

The build uses the local Rider 2026.2 found in `~/Applications` or `/Applications`; pass
`-PriderLocalPath=/path/to/Rider.app` to use another one.

## Install, disable, uninstall

- **Install**: *Settings | Plugins | ⚙ | Install Plugin from Disk…* and pick the zip. No restart needed.
- **Settings**: *Settings | Appearance & Behavior | Glass Islands*. Changes preview live.
- **Toggle**: *View | Appearance | Glass Islands*, or *Find Action* → "Glass Islands" (assignable to a shortcut).
- **Disable or uninstall**: *Settings | Plugins*, no restart needed. Rider is restored exactly as it was.
- **Safe mode**: if Rider stops while the glass is being applied, the plugin turns itself off at the next start
  and says so in a notification.
- **Diagnostics**: *Help | Diagnostic Tools | Glass Islands Debug Information* writes
  `glass-islands-report.txt` next to `idea.log` (native state, cost of the plugin on the UI thread, glass
  windows) and opens it.

## How it works

```
NSWindow (Rider project frame, made non-opaque)
└─ NSThemeFrame
   ├─ backdrop        NSVisualEffectView, whole window, tinted          ┐ added by the plugin,
   ├─ islands         shadows │ frosted panes │ Liquid Glass pills       ┘ below Rider's content
   ├─ AWTView         Rider's Swing content (Metal layer, non-opaque)
   └─ titlebar
```

1. **Finding the window.** `MacUtil.getWindowFromJavaWindow` (platform API) returns the `NSWindow*` of a frame.
   The native side only ever acts on a pointer that is still one of `NSApp.windows`.
2. **Making Rider translucent.** Decorated AWT frames cannot be translucent through public API, so the plugin
   reproduces what `Window.setBackground` does for undecorated windows: transparent `Component.background`,
   `LWWindowPeer.setOpaque(false)`, and classic double buffering for the root pane (IntelliJ's per-window buffer
   flips with SrcOver, which would accumulate translucent pixels). Swing surfaces that the glass replaces become
   transparent through developer-level UIManager overrides, editors through their own scheme delegate.
3. **Shapes.** The plugin follows the Islands layout (tool-window holders, editor splitters, toolbar and stripe
   buttons, editor tabs) with one AWT event listener, coalesced to one pass per UI cycle, and sends the native
   side only what changed. Native views are keyed by a stable id and updated in place inside a
   `CATransaction` without implicit animations.
4. **Calling AppKit.** `libliquidglass.dylib` exposes a plain C ABI (integers and double arrays) called through
   Java 25's Foreign Function & Memory API. Every call runs on the AppKit main thread and waits for it in the
   same run-loop modes as the JDK's own AWT natives, so it cannot deadlock and the native shapes change in the
   same frame as the Swing content. Objective-C exceptions are caught in the bridge. The library is loaded from a
   copy named after its content (so an update without restart loads the new native code) and closed when the
   plugin unloads.
5. **Other windows.** One listener sees Rider's windows open: popups owned by a glass window get a whole-window
   backdrop, floating tool windows too, and detached editor windows get their own controller with islands.
6. **Unloading.** Everything the plugin changes is recorded and restored: window opacity, UIManager keys, editor
   colours, button looks, native views, listeners. The plugin never opens editors or dialogs of its own, whose
   stack traces or accessibility caches could keep it in memory.

Everything that depends on JDK or IntelliJ implementation details is listed and checked once in
`internals/PlatformInternals.kt`; if a future Rider changes one, only the feature built on it stops, and the
settings page says why.

## What is native, and what is not

- The blur, vibrancy, Liquid Glass refraction and the dimming of controls in inactive windows are rendered by
  macOS (`NSVisualEffectView`, `NSGlassEffectView`). Nothing is painted to imitate them.
- The **large panes use the frosted material, not Liquid Glass**: `NSGlassEffectView` turns flat or loses its blur
  when a window resigns key, which makes a whole IDE flash on every focus change. Liquid Glass is used where macOS
  itself uses it: buttons, pills and tabs.
- Tints, the specular rim and the shadows are Core Animation layers configured by the plugin on top of the
  native material.
- Swing text cannot use AppKit vibrancy; readability comes from the tint opacity instead (the editor is always
  at least 86% opaque by default).

## Flicker

Two mechanisms can make a translucent Swing window flash, and both are handled:

1. **Opaque components that no longer paint their background.** In a translucent window Swing clears a repainted
   region with the background of the first opaque component above it, and skips whatever an opaque sibling
   covers. The plugin paints each opaque component of a glass window once offscreen and makes the ones that do
   not cover their bounds non-opaque (`OpacityGuard`), on layout, focus and activation changes.
2. **Frames presented before they are complete.** JBR's Metal pipeline presents frames from the display link; on a
   translucent window a frame caught mid-update shows a panel (or the whole window) blank for one frame. Screen
   recordings of a scrolled diff: 2–3 blank frames per 12 s by default, none with
   `-Dsun.java2d.metal.displaySync=false`. JBR reads it at startup, so the plugin offers to add it to the user VM
   options (*Settings | Appearance & Behavior | Glass Islands*, or a one-time notification) and restarts
   Rider; it removes the option again when the plugin is uninstalled.

## Measured cost

On an M4 Pro, Rider idle: 1.7 % CPU with the glass, 2.1 % without (noise). Typing, scrolling and caret blinking
send nothing to AppKit; a full layout scan takes about 0.2 ms, a native update 2–5 ms (15 ms once, when the views
are created). WindowServer composites the blur like for any vibrant macOS window: a few percent. The debug report
shows these counters live.

## Limits

- Needs an Islands theme; with the Classic UI, a non-Islands theme or High Contrast the glass pauses and the
  settings say why.
- Dialogs keep Rider's regular look. Popups open while the theme changes (e.g. the Quick Switch theme preview) go
  back to Rider's own look until they close: their list renderers keep colours of the previous theme.
- Hints Rider draws inside the window itself (lightweight popups, not separate windows) keep Rider's look.
- Relies on implementation details of Rider 2026.2 and JBR 25 (see `PlatformInternals`); `untilBuild` is
  `262.*` so that a new major Rider is verified before the plugin loads in it.

## License

[MIT](LICENSE) © 2026 Lilyan Muller
