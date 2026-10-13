# Code Scroll

MIT-licensed code minimap for IntelliJ-based IDEs. Runtime dependencies are
limited to Java/Swing and the host JetBrains Platform. No image libraries,
network clients, bytecode agents or third-party runtime JARs are bundled.

## Features

- Syntax-colored software raster from the editor's own incremental highlighter,
  with bounded dimensions and large-file sampling, rendered at the screen's
  device scale (sharp on HiDPI). CLEAN draws solid strokes with a light row
  between lines; ACCURATE shapes each character by the ink in its upper and
  lower half (measured once from the platform's monospaced font), so capitals,
  ascenders and descenders are visible.
- Fit, Fill and proportional modes; draggable viewport, mouse-wheel scrolling,
  Shift-click selection and optional caret preservation.
- Click on mouse DOWN, UP or NONE (drag only); optional column-precise clicks.
- Hover preview in a read-only editor with the theme's real syntax colors;
  configurable delay and optional mouse-wheel scrolling inside the preview.
- Separate overlays for diagnostics, VCS ranges, bookmarks and search/usage
  results, each switchable, with optional full-line diagnostics and markup.
- Comment-marker words, a restricted custom marker regex, declarations with
  name and annotation filters, and Markdown headings (Markdown files only).
- Rendered Markdown preview minimap (optional module, loaded with the bundled
  Markdown plugin).
- Left/right alignment, drag resizing, separate widths for main, diff, preview,
  console and untyped editors, automatic narrow-editor width and width lock.
- Show-on-hover mode, a quick-hide button and "Hide in This Editor".
- Overlay colors (viewport, border, caret line, selection, marker and
  declaration labels) follow the editor color scheme by default; each can be
  overridden with a color picker. Adjustable text intensity softens the map
  against the editor background.
- Proportional mode by default (3 px per line): the viewport frame is the
  editor's visible area at map scale, clamped to the document, and travels from
  the top of the map to its bottom with the scroll while the map scrolls under
  it. The raster covers up to 4096 device rows and the next window is rendered
  before the shown part reaches its edge, so scrolling just moves the image;
  a render in flight is never cancelled by further scrolling.
  Fit squeezes the whole file into the panel; Fill also stretches short files.
- The frame is a translucent fill without a border by default; it brightens
  while hovered and dragged.
- Independent switches per editor kind, empty maps for console/untyped editors
  or outside line limits, extension exclusions and a View-menu toggle.
- Frontend-only modular packaging, with a split-mode development sandbox.
- Cancellable background reads, debounced rendering, event-driven repaints and
  disposable editor listeners; dynamic install/unload without restart.

Settings: **Settings | Editor | Code Scroll**. Use **View | Enable/Disable Code
Scroll Minimap**, Find Action ("Toggle Code Scroll Minimap"), or the minimap
context menu. No default shortcut is assigned.

## Build And Test

Use JDK 21+ for the baseline or JDK 25 for current 2026 IDE distributions.
The bytecode/API target remains Java 21. Gradle is supplied by the wrapper.

```sh
./gradlew :frontend:test buildPlugin verifyPluginStructure verifyPlugin
```

Install `build/distributions/code-scroll-0.1.0.zip` through **Plugins | Install
Plugin from Disk**. Sandbox development: `./gradlew runIde`; add
`-PlocalIdePath=<IDE>/Contents` to run an installed IDE (for example WebStorm)
and `-PopenPath=<dir>` to open a project in it.

See [TESTING.md](TESTING.md) for verification results, EAP commands, generated
regression fixtures and manual acceptance scenarios.

## Compatibility And Current Limits

The declared baseline is IntelliJ Platform **2025.3 / build 253**, without a hard
upper build ceiling. Future IDE releases require separate verification.
The plugin builds against IDEA 2025.3.6.1. Plugin Verifier reports it compatible
with that build, IDEA 2026.2.3 and WebStorm 2026.3 EAP (263.6800.25). The only
reported item is one experimental Markdown preview API used by the optional
Markdown module. A WebStorm EAP sandbox starts with the plugin without errors;
the full manual GUI checklist and a real Gateway session are still pending.

- The map uses logical document lines rather than visual wrap/fold rows.
- Declaration detection uses conservative text heuristics, not language-specific PSI.
- Labels are scanned in the first 1 MiB. Syntax colors come from the editor's
  highlighter for the sampled lines only, capped at 200,000 tokens per frame.
- Compressed maps sample up to three document lines per pixel row. Long lines are
  clipped horizontally at 512 characters. The maximum raster is 600 x 4096 device
  pixels (about 9.4 MiB); a typical 110 px map on a 2x screen uses about 3.6 MiB.
- Labels are cached per document version; scrolling does not rescan the text.
- Rows shared by several lines, or partly covered by a line, are antialiased by
  coverage (up to 8 lines sampled per row), so thin and fractional lines fade
  smoothly instead of flickering.
- Label overlaps are resolved in document coordinates, so labels do not blink
  or vanish at the panel edges while scrolling.
- `region`, `endregion` and `MARK` only count at the start of a comment; marker
  words written in capitals are case-sensitive. `#` starts a comment only in
  languages that use it (not in C-family, JavaScript/TypeScript or CSS files,
  except `#region`/`#endregion`/`#pragma` directives).
- Errors and warnings can be hidden separately (settings, minimap context menu
  and View | Toggle Code Scroll Diagnostics). Methods are not labelled unless
  the "Method names" filter selects them; classes and types always are.
- The frame height is measured in logical lines, so folded imports, inlays and
  soft wraps keep it aligned with the code on screen.
- Editors in modal dialogs (settings previews) get no minimap, except diffs.
- The hover preview is the platform's editor-fragment view: real colors,
  folding and line numbers (line numbers can be turned off).
- A press outside the frame centers the frame under the pointer and keeps
  dragging it while the button is held.
- Long files keep a coarse whole-document raster under the precise window, so
  fast drags never show a blank map while the window catches up.
- Settings are indexed for the IDE's settings search.
- Navigating with the rendered Markdown map also scrolls the source editor
  (can be turned off).
- Overlay categories are inferred from public highlighter properties (attribute
  keys, thin stripes, gutter renderers), not backend daemon classes, so an
  unusual third-party highlighter can land in the wrong category.
- The hover preview is plain text for documents above 2 MiB.
- The rendered Markdown map relies on an experimental Markdown preview API.
- Side attachment requires an unoccupied BorderLayout side slot.
  Unsupported or occupied layouts are left unchanged.
- Frontend-only remote packaging exists, but real Gateway behavior needs testing.

## Dependencies And License

Project code is under [MIT](LICENSE); the license is included in the main plugin
JAR. Build tooling uses Gradle and the official IntelliJ Platform Gradle Plugin.
Tests use JUnit Jupiter; the baseline IDE test bootstrap also requires JUnit 4.
The rendered Markdown script has Node.js tests (`./gradlew :markdown:testJavaScript`).
Build/test dependencies are not bundled in the plugin ZIP.

The generated Gradle wrapper remains Apache-2.0 build tooling. JetBrains IDEs
and SDK libraries retain their own licenses and are not distributed with the plugin.
