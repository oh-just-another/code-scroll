# Working in This Repository

Code Scroll is a code minimap for IDEs built on the IntelliJ Platform. Rustam Garifulin wrote it from scratch and
publishes it under the MIT license. This file explains how the
repository is laid out and what to keep in mind when you change it.

User-facing documentation lives in `README.md`.

## What You Are Working With

The plugin is written in Kotlin and built with Gradle and the IntelliJ Platform Gradle Plugin. Both the oldest supported
platform (`sinceBuild`) and the default SDK are set in `build.gradle.kts`, so check them there rather than assuming
a version.

The plugin ships only its own code. It runs on the Kotlin standard library that comes with the IDE, which is why
`gradle.properties` sets `kotlin.stdlib.default.dependency=false`. If a change seems to need a third-party library,
ask the owner before adding it.

## How the Code Is Organized

The root project only packages the plugin. Its `src/main/resources/META-INF/plugin.xml` holds the plugin id, the
vendor and the description, and it loads two content modules:

- `frontend/` is the minimap itself. The IDE always loads it.
- `markdown/` adds a minimap to the rendered Markdown preview. The IDE loads it only when the bundled Markdown plugin
  is enabled.

The `frontend` module depends on nothing but `intellij.platform.frontend`, so the plugin also runs in JetBrains Client
during Remote Development. Backend classes such as `HighlightInfo` or `DaemonCodeAnalyzer` are not available there.
When you need to know what the editor highlights, read it from the public properties of `RangeHighlighter` and
`EditorHighlighter`, as `MarkupClassifier` does.

Each file in `frontend/src/main/kotlin/dev/codescroll/` has one job:

| File | What it does |
|------|--------------|
| `CodeScrollManager.kt` | Decides which editors get a minimap and adds it to the editor's `BorderLayout`. It steps aside when another plugin already uses that side. |
| `MinimapPanel.kt` | The minimap component. It renders in the background, handles clicks, dragging and the hover preview, and paints markup and labels over the raster. |
| `MapGeometry.kt` | Maps document lines to minimap pixels and places the viewport frame in Fit, Fill and proportional modes. |
| `MapRaster.kt` | Turns document lines into an image inside a read action, coloring tokens with its own syntax highlighters. |
| `RasterRenderer.kt` | Draws sampled lines into a `BufferedImage` with antialiasing in the CLEAN or ACCURATE style. |
| `MarkerScanner.kt` | Finds comment markers such as TODO and `region`, declarations and Markdown headings for labels. |
| `MarkupClassifier.kt` | Sorts editor highlighters into errors, VCS changes, bookmarks, breakpoints and search results. |
| `LabelLayout.kt` | Chooses labels that do not overlap. |
| `PreviewPopup.kt` | Shows the code under the mouse in a popup built on `EditorFragmentComponent`. |
| `CodeScrollSettings.kt` | Stores the settings in `CodeScroll.xml`. `State.validate()` clamps values read from disk. |
| `CodeScrollConfigurable.kt` | The Settings → Editor → Code Scroll page, built with Kotlin UI DSL. |
| `CodeScrollColors.kt` | Color keys that each editor color scheme can override, and their Color Scheme page. |
| `Actions.kt`, `Listeners.kt` | View menu actions, and the listeners that attach minimaps to new editors and after a plugin reload. |
| `Diagnostics.kt` | The "Code Scroll: Dump Diagnostics" action, which reports what the minimap sees in an editor. |

Extensions, actions and listeners are declared in `frontend/src/main/resources/code-scroll.frontend.xml`. The
Markdown module keeps its Kotlin side in `RenderedMarkdownMinimapProvider.kt`, the script that runs inside the
preview in `rendered-minimap.js`, and its declarations in `code-scroll.markdown.xml`.

Unit tests for the frontend sit in `frontend/src/test/kotlin/dev/codescroll/` and run without starting an IDE. The
Markdown script has Node tests in `markdown/src/test/js/minimap.test.cjs`. To exercise the plugin on large and unusual
files, run `tools/GenerateFixtures.java`, which writes them to `build/fixtures`. CI in
`.github/workflows/verify.yml` runs the tests and Plugin Verifier against the baseline SDK, the latest release and
the current EAP.

Everything under `build/`, `.gradle/`, `.kotlin/`, `.intellijPlatform/` and `.idea/` is generated or local. Don't
edit or commit it.

## Building and Running

You need JDK 25. If IntelliJ IDEA is installed, point `JAVA_HOME` at its bundled runtime:

```sh
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
```

| Command | What it does |
|---------|--------------|
| `./gradlew :frontend:test` | Runs the unit tests. |
| `./gradlew :markdown:testJavaScript` | Runs the Markdown script tests. Needs Node. |
| `./gradlew buildPlugin` | Builds the zip in `build/distributions/`, including the settings search index. |
| `./gradlew verifyPlugin` | Runs Plugin Verifier. |
| `./gradlew runIde` | Starts a sandbox IDE with the plugin. |

These Gradle properties change how the build runs:

| Property | Effect |
|----------|--------|
| `-PlocalIdePath=<IDE>.app/Contents` | Builds and runs against an installed IDE instead of downloading an SDK. |
| `-PplatformVersion=<version or build>` | Uses another SDK. CI uses this for the release and EAP targets. |
| `-PverifyIdePaths=<path>\|<path>` | Runs Plugin Verifier against installed IDEs. |
| `-PopenPath=<project>\|<file>` | Opens a project and a file in the sandbox. |
| `-PtrustProjects=true` | Skips the trust dialog in the sandbox. |
| `-PcodeScrollDiagnostics=true` | Logs a diagnostics report for every editor that gets a minimap. |
| `-PsplitMode=true` | Runs the sandbox as a backend with JetBrains Client, as in Remote Development. |
| `-PsandboxJvmArgs="..."`, `-PjcefDebugPort=<port>` | Pass extra JVM options to the sandbox, or open a debug port for the Markdown preview. |

## Keeping the Disk Free

Every IDE SDK that Gradle downloads takes about 4 GB in its cache, and every sandbox run writes gigabytes of
indexes. On the owner's machine this has filled the disk before, so build against the installed IDE with
`-PlocalIdePath` and check free space with `df -h /` before a heavy run.

When you finish testing in a sandbox, delete its `system_runIde` and `log_runIde` folders under
`.intellijPlatform/sandbox/`. If a change to the build made Gradle unpack the same SDK again into a new folder under
`~/.gradle/caches/*/transforms`, delete the old copy.

## Making Changes

Start with `git status --short`. If you see changes you didn't make, they belong to the owner, so leave them as
they are.

Code Scroll is written independently, so don't copy code from other plugins or projects, and don't name them in
code, comments, documentation or commit messages. Avoid internal IDE classes, reflection and Java agents. Use an experimental API only when there is no public alternative, and mention it in the README.

Don't raise the minimum platform, Gradle or Kotlin version without the owner's agreement. Keep local paths,
certificates and tokens such as `PUBLISH_TOKEN` out of the repository.

Most changes touch more than one file. Check the list that matches yours:

- **A new setting.** Add the field to `CodeScrollSettings.State`, clamp it in `validate()`, add a control to
  `CodeScrollConfigurable` and describe it in the README. Add a test to `CodeScrollSettingsTest` when the value has a
  range.
- **A new color.** Add a key with a fallback to `CodeScrollColors` and a descriptor to its Color Scheme page. An
  empty value in the settings means "use the color scheme".
- **A new action or extension.** Declare it in `code-scroll.frontend.xml`, or in `code-scroll.markdown.xml` for the
  Markdown module.
- **Rendering or scrolling.** `MinimapPanel`, `MapGeometry`, `MapRaster` and `RasterRenderer` work together. Keep
  heavy work off the EDT and call `ProgressManager.checkCanceled()` in long loops, so the EDT only paints a finished
  image.
- **The rendered Markdown minimap.** When you change `rendered-minimap.js`, add or update a test in
  `minimap.test.cjs`.
- **Anything a user can see.** Update the README.

## Code Style

Match the surrounding code: Kotlin with four-space indentation, and lines kept near 120 characters where that reads
well. Write comments and KDoc in English, and use them to explain why the code does something rather than to retell
it.

Classes live in `dev.codescroll`, and Markdown classes in `dev.codescroll.markdown`. Visible text is English and
written inline, since the plugin has no resource bundle yet. When logic doesn't need Swing, such as geometry, marker
scanning or highlighter classification, keep it in a plain class and cover it with unit tests.

## Testing

Before a commit, run `./gradlew :frontend:test`, and also `:markdown:testJavaScript` if you changed the script.
Before a release, run Plugin Verifier on every target IDE with `-PverifyIdePaths`, then check the plugin by hand
in a sandbox of each IDE.

When you test in a sandbox, work only with the sandbox windows. The owner usually has their own IDEs open, so don't
click in them or close them. Capture screenshots of the sandbox window only, never of the whole screen. If you
checked something only with a build and unit tests, say so in your report rather than calling it tested.

## Commits

Write the subject in English as `type: summary`, where the type is `feat`, `fix`, `refactor`, `test`, `docs`,
`build` or `ci`. When the reason for a change isn't obvious from the diff, explain it in the body.

Commit only when the owner asks. Don't add `Co-Authored-By`, `Claude-Session` or any other signature or attribution
lines.
