# ATLAS JetBrains Plugin

A JetBrains IDE client for the [ATLAS](https://github.com/inferstep/ATLAS) agent proxy — a thin UI layer wrapping `atlas-proxy`'s agent loop with no agent logic in the plugin itself.

**Status: Stage 1 scaffold.** Tracking [issue #35](https://github.com/inferstep/ATLAS/issues/35). This stage adds the plugin skeleton: a static ATLAS tool window that reports the plugin is installed and nothing more. Chat, permissions, diffs and workspace integration arrive in later stages, one pull request each.

## How it works

The plugin is a thin client over the proxy HTTP API (see `docs/API.md`). The protocol module (`protocol/`) holds the proxy's endpoint paths and carries no IntelliJ dependency, so the wire contract can be understood and tested on its own:

* `POST /v1/agent` — streams a turn (text tokens, tool calls/results, permission requests) as server-sent events
* `POST /v1/permission` — answers permission requests raised mid-turn
* `POST /cancel` — cancels the in-flight turn
* `GET /ready`, `GET /health`, `GET /version` — status routes, no service token required

The layout mirrors `extensions/vscode/`, which is the reference IDE client; the TUI (`tui/`) remains the reference client overall.

## Requirements

* JDK 21 (the Gradle toolchain pins 21; nothing else is installed for you)
* IntelliJ Platform 2026.1 or newer (`sinceBuild = 261`)

The plugin is written in Kotlin against the IntelliJ Platform 2026.1.3 SDK. Kotlin is pinned to language and API level 2.3 because the 2026.1 IDE bundles the 2.3.x standard library; compiling against a newer level produces bytecode the platform cannot load.

## Building and running

All commands run from `extensions/jetbrains/`:

```bash
./gradlew build            # compile and assemble
./gradlew runIde           # launch a sandboxed IntelliJ IDEA with the plugin
./gradlew runPyCharm       # launch sandboxed PyCharm
./gradlew runWebStorm      # launch sandboxed WebStorm
./gradlew runGoLand        # launch sandboxed GoLand
./gradlew test             # protocol unit tests
./gradlew ktlintCheck      # Kotlin formatting and lint gate
./gradlew ktlintFormat     # apply the same rules
./gradlew buildPlugin      # build the distributable ZIP
```

The four run tasks download a full IDE on first use, which is large and slow. They exist so the plugin can be smoke-tested against each product rather than assuming IDEA compatibility.

## Kotlin style

`ktlint` is the Kotlin gate, and `extensions/jetbrains/.editorconfig` is the single source of the rules it applies — there is no baseline file and no rule configuration in the Gradle build. Lines are limited to 100 characters, matching the other languages in this repository.

Kotlin is intentionally **not** covered by `scripts/code_health.py`, which scans the Go and Python trees only. Formatting is ktlint's concern; the function- and file-size rules in `docs/CODE_STYLE.md` are not mechanically enforced for this language yet.

## Layout

```
build.gradle.kts        # plugin module: IPGP, Kotlin toolchain, ktlint, run tasks
settings.gradle.kts     # root project + protocol module
protocol/               # proxy endpoint contract, no IntelliJ dependency
src/main/kotlin/        # tool window and, later, the client and session layers
src/main/resources/     # META-INF/plugin.xml
.editorconfig           # ktlint rules (single source)
```