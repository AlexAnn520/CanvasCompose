# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CanvasCompose is an Android drawing app written in Kotlin with Jetpack Compose. Package: `com.sample.canvascompose`. Single-module project (`:app`). Supports freehand, line, and rectangle shapes with selection/move, undo/redo, and JSON save/open via SAF.

A detailed design document (Chinese) lives at `DEVELOPMENT.md` — covers the architecture diagram, data flow, Compose concepts used, and the algorithms behind hit-testing and undo/redo. Read it before making structural changes.

## Build & Run

```bash
./gradlew assembleDebug                # debug APK
./gradlew assembleRelease              # release APK (minify disabled in buildTypes)
./gradlew test                         # JVM unit tests
./gradlew test --tests "com.sample.canvascompose.ExampleUnitTest"   # single test class
./gradlew connectedAndroidTest         # instrumented tests (needs device/emulator)
./gradlew clean
```

## Tech Stack

- **Kotlin 2.0.21** + **Jetpack Compose** (BOM 2024.09.00), **Material 3**
- **kotlinx.serialization 1.7.3** (`kotlin-serialization` plugin) for shape JSON
- **androidx.lifecycle.viewmodel-compose 2.9.2** for `viewModel()` in Composables
- **AGP 8.12.1**, compileSdk/targetSdk 36, minSdk 24, Java 11
- Version catalog at `gradle/libs.versions.toml`

## Architecture

Unidirectional data flow, single-activity Compose app:

```
MainActivity → DrawingApp() → DrawingScreen(viewModel, onSave, onOpen)
                                   ↓ collectAsState
                             DrawingViewModel (StateFlow<DrawingState>)
                                   ↓
                             List<Shape>  (sealed: Line | Rectangle | Freehand)
```

- `MainActivity.kt` — registers SAF launchers (`CreateDocument("application/json")`, `OpenDocument()`) and passes `onSave`/`onOpen` callbacks into `DrawingScreen`. File I/O uses `ContentResolver` — **do not** add runtime storage permissions; SAF is the chosen pattern.
- `viewmodel/DrawingViewModel.kt` — single source of truth. `MutableStateFlow<DrawingState>` private, `StateFlow` exposed. Undo/redo is **snapshot-based** (not command-based): `pushUndo()` is called before every mutation to copy the current `shapes` list. For drag-move, the snapshot is taken once at drag start via `pushMoveStart()`.
- `data/Shape.kt` — `@Serializable sealed class Shape` with `@SerialName`-tagged subclasses for polymorphic JSON. Shapes are **immutable**; `translate()` returns a new instance via `copy()`. `color` is `Long` (ARGB) and coordinates are `Float` because Compose's `Color`/`Offset` aren't directly serializable by kotlinx.serialization.
- `ui/DrawingCanvas.kt`, `DrawingToolbar.kt`, `DrawingScreen.kt` — Compose UI. The canvas uses `pointerInput` + `detectDragGestures`; the `key` passed to `pointerInput` is the current tool, so switching tools re-registers gesture handling.

### Patterns to preserve

- **Immutable state updates only** — use `_state.value = _state.value.copy(...)`; never mutate `shapes` in place. Undo/redo relies on old snapshots being safe to hold.
- **Call `pushUndo()` before every mutating operation.** If you add a new mutation method on `DrawingViewModel`, it must snapshot first or undo will skip it.
- **Extending shapes**: add a new `@Serializable @SerialName("…") data class` subclass of `Shape` with `hitTest` + `translate`, then extend the `when` in `DrawingCanvas.kt`. The sealed class makes the compiler flag any missed branch.
- **Transient drag state** (preview line/rect while finger is down) belongs in the Canvas composable via `remember { mutableStateOf(...) }` — it's not business state and does not go through the ViewModel.

## Code Conventions

- Kotlin style: `official` (set in `gradle.properties`)
- AndroidX enabled, non-transitive R classes
- Add dependencies via the version catalog (`libs.xxx` in `gradle/libs.versions.toml`), not inline in `build.gradle.kts`

## AI agent skills

This repo is wired up with Google's official Android skills (see
[developer.android.com/tools/agents](https://developer.android.com/tools/agents)).

- **Project-local skills** live under `.claude/skills/`:
    - `edge-to-edge` — adaptive edge-to-edge in Compose (applies to `DrawingScreen`)
    - `agp-9-upgrade` — migrate off the current AGP 8.12.1
    - `r8-analyzer` — audit R8 keep rules (relevant once `minifyEnabled = true`)
      Agents auto-activate these when a prompt matches their frontmatter; no manual
      invocation needed.
- **Full catalog** (all 6 official skills + dynamic lookup) is available through the
  `android-skills` MCP server registered at user scope. Tools:
  `mcp__android-skills__list_skills`, `search_skills`, `get_skill`. Use it when the
  project-local subset doesn't cover a task (e.g. adding Navigation 3, Play Billing).
- **Example trigger prompts**: *"Make the drawing screen edge-to-edge"*,
  *"Upgrade this project to AGP 9"*, *"Audit the R8 config"*.
- **Adding a skill to the repo**: `npx -y android-skills-pack install --target claude-code --skill <name>`.
  `npx -y android-skills-pack list` prints the available skill IDs.
