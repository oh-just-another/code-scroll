# Code Scroll

MIT-licensed code minimap for IntelliJ-based IDEs. Runtime dependencies are
limited to Java/Swing and the host JetBrains Platform. No image libraries,
network clients, bytecode agents or third-party runtime JARs are bundled.

## Features

- Syntax-colored software raster with bounded dimensions and large-file sampling.
- Fit and proportional navigation, draggable viewport, mouse-wheel scrolling,
  Shift-click selection and optional caret preservation.
- Escaped seven-line hover previews, caret/selection and IDE markup overlays.
- Comment, region, declaration and Markdown heading labels.
- Left/right alignment, drag resizing, automatic narrow-editor width and width lock.
- Independent switches for main, diff, preview, console and untyped editors.
- Persistent settings, extension exclusions, line-count limits and a View-menu toggle.
- Frontend-only modular packaging, with a split-mode development sandbox.
- Cancellable background reads, debounced rendering and disposable editor listeners.

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
Plugin from Disk**. Sandbox development: `./gradlew runIde`.

See [TESTING.md](TESTING.md) for verification results, EAP commands, generated
regression fixtures and manual acceptance scenarios.

## Compatibility And Current Limits

The declared baseline is IntelliJ Platform **2025.3 / build 253**, without a hard
upper build ceiling. Future IDE releases require separate verification.
The plugin builds against IDEA 2025.3.6.1. Binary compatibility was verified
with that build, IDEA 2026.2.3, GoLand 2026.2 and WebStorm 2026.3 EAP.
GUI and actual Gateway acceptance tests have not yet been run.

- Markdown source maps are supported; a separate rendered preview map is pending.
- The map currently uses logical document lines rather than visual wrap/fold rows.
- Declaration detection uses conservative text heuristics, not language-specific PSI.
- Labels and syntax scanning are limited to the first 1 MiB. Syntax processing
  also has a 50,000-token and 40 ms cooperative budget; remaining text is monochrome.
- Compressed maps sample up to three document lines per pixel row. Long lines are
  clipped horizontally. The maximum raster is 300 x 4096 pixels, about 4.7 MiB.
- IDE error-stripe/background highlights have a bounded iteration budget.
  Dedicated bookmark labels, complete VCS range tracking, annotation filters,
  hover-to-show and separate widths per editor kind are pending.
- Side attachment currently requires an unoccupied BorderLayout side slot.
  Unsupported or occupied layouts are left unchanged.
- Frontend-only remote packaging exists, but real Gateway behavior needs testing.

## Dependencies And License

Project code is under [MIT](LICENSE); the license is included in the main plugin
JAR. Build tooling uses Gradle and the official IntelliJ Platform Gradle Plugin.
Tests use JUnit Jupiter; the baseline IDE test bootstrap also requires JUnit 4.
Build/test dependencies are not bundled in the plugin ZIP.

The generated Gradle wrapper remains Apache-2.0 build tooling. JetBrains IDEs
and SDK libraries retain their own licenses and are not distributed with the plugin.
