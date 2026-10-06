# ATLAS JetBrains Plugin

A JetBrains IDE client for the [ATLAS](https://github.com/inferstep/ATLAS) agent proxy — a thin UI layer wrapping `atlas-proxy`'s agent loop with no agent logic in the plugin itself.

**Status: Stage 1 technical spike.** Tracking [issue #35](https://github.com/inferstep/ATLAS/issues/35). This stage proves a Jewel Compose panel can mount in the ATLAS tool window, follow the IDE theme, recompose incrementally as frames arrive, and be covered by tests. The frames are a stub, not the proxy. Chat, permissions, diffs, workspace integration, and all protocol/client logic arrive in later stages, one pull request each.

## Spike results

| Question | Result |
|---|---|
| Compose panel mounts in a Swing tool window | Works, once `plugin.xml` depends on `com.intellij.modules.compose` |
| Incremental streaming recomposition | Works — each stub frame appends and recomposes |
| IDE theme bridging | Works via `SwingBridgeTheme` |
| Jewel Markdown rendering | **Not reachable from a third-party plugin** (see below) |

`composeUI()` only adds a *compile-time* dependency. Without the matching `<depends>com.intellij.modules.compose</depends>` in the descriptor the tool window throws `NoClassDefFoundError: androidx/compose/ui/awt/ComposePanel` on a product that does not enable Compose for other plugins — PyCharm 2026.1 was the one that caught it, even though the class is in its distribution.

Jewel's Markdown renderer is a separate matter: `intellij.platform.jewel.markdown.*` modules ship in every 2026.1 product (they are in the `compose` module set) but none of them declares a plugin-visible id. In the whole PyCharm 2026.1 distribution the only Compose-family id a plugin can depend on is `com.intellij.modules.compose`, and that module depends only on Jewel's foundation, UI and IDE-LAF bridge modules. Declaring `<depends>intellij.platform.jewel.markdown.core</depends>` does not pull the module in — it makes the platform refuse to load the plugin at all. Assistant text therefore streams through Jewel `Text` for now; Markdown rendering waits until the platform exposes a depend-able id. `AtlasToolWindowFactoryTest` guards both halves of this.

## How it works

The plugin will be a thin client over the proxy HTTP API (see `docs/API.md`) in Stage 2. This Stage 1 spike deliberately has no HTTP, endpoint, or protocol code. The layout mirrors `extensions/vscode/`, which is the reference IDE client; the TUI (`tui/`) remains the reference client overall.

## Requirements

* JDK 21 (the Gradle toolchain pins 21; nothing else is installed for you)
* IntelliJ Platform 2026.1 or newer (`sinceBuild = 261`)

The plugin is written in Kotlin 2.3.20 against the IntelliJ Platform 2026.1.3 SDK. Kotlin language and API levels are pinned to 2.3 because the IDE bundles the 2.3.x standard library; compiling against a newer level produces bytecode the platform cannot load.

The spike uses the Compose runtime and Jewel modules bundled with IntelliJ Platform 2026.1.3: Compose Multiplatform 1.10.0 and Jewel 0.37. `composeUI()` supplies the Compose modules (including the runtime split) and, transitively, the Jewel widgets the plugin uses; nothing is packaged into the plugin. The build-side declaration is only half of it, though: `plugin.xml` depends on `com.intellij.modules.compose`, and that is what puts the classes on the plugin's classloader at runtime. `SwingBridgeTheme` maps the active Swing Look and Feel into the Compose content.

## Building and running

All commands run from `extensions/jetbrains/`:

```bash
./gradlew build            # compile and assemble
./gradlew runIde           # launch a sandboxed IntelliJ IDEA with the plugin
./gradlew runPyCharm       # launch sandboxed PyCharm
./gradlew runWebStorm      # launch sandboxed WebStorm
./gradlew runGoLand        # launch sandboxed GoLand
./gradlew test             # BasePlatformTestCase tool-window integration test
./gradlew ktlintCheck      # Kotlin formatting and lint gate
./gradlew ktlintFormat     # apply the same rules
./gradlew buildPlugin      # build the distributable ZIP
```

The four run tasks download a full IDE on first use, which is large and slow. They exist so the plugin can be smoke-tested against each product rather than assuming IDEA compatibility.

## Smoke-test status

Each `runIde`-family task launches a sandboxed IDE with the built plugin, so the tool window can be checked in that product by hand. These are manual runs, not tests, and none of them is wired into CI.

| Run task | Product | Smoke-tested by hand |
|---|---|---|
| `runIde` | IntelliJ IDEA | no |
| `runPyCharm` | PyCharm 2026.1 | **yes** — `PY-261.22158.340` |
| `runWebStorm` | WebStorm 2026.1 | no |
| `runGoLand` | GoLand 2026.1 | no |

The PyCharm run is the one that reproduced the Stage 1 `NoClassDefFoundError` above. With the tool window forced visible so its content factory actually runs, the plugin loads, the tool window renders, and no exception names the plugin. The other three have not been launched — their sandboxes have no project or tool-window state, so the tool window was never exercised. Treat four-way compatibility as unverified until each run task has been smoke-tested.

## Kotlin style

`ktlint` is the Kotlin gate, and `extensions/jetbrains/.editorconfig` is the single source of the rules it applies — there is no baseline file and no rule configuration in the Gradle build. Lines are limited to 100 characters, matching the other languages in this repository.

Kotlin is intentionally **not** covered by `scripts/code_health.py`, which scans the Go and Python trees only. Formatting is ktlint's concern; the function- and file-size rules in `docs/CODE_STYLE.md` are not mechanically enforced for this language yet.

## Layout

```
build.gradle.kts        # plugin module: IPGP, bundled Compose/Jewel, Kotlin toolchain, ktlint
settings.gradle.kts     # root project
src/main/kotlin/        # Compose tool window and, later, the client and session layers
src/main/resources/     # META-INF/plugin.xml
.editorconfig           # ktlint rules (single source)
```
