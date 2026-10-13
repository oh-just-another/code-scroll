# Code Scroll

MIT-licensed code minimap for IntelliJ-based IDEs. Runtime dependencies are
limited to Java/Swing and the host JetBrains Platform. No image libraries,
network clients, bytecode agents or third-party runtime JARs are bundled.

## Features

- Syntax-colored software raster from the editor's own incremental highlighter,
  with bounded dimensions and large-file sampling. CLEAN (solid strokes) and
  ACCURATE (glyph-coverage shading) render styles.
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
- The viewport frame is as tall as the editor's visible area at map scale, so
  it stays easy to grab in short and empty files.
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
  clipped horizontally. The maximum raster is 300 x 4096 pixels, about 4.7 MiB.
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
