# Contributing to OmniSign

Thanks for your interest in contributing! This file covers the essentials; for the full architecture, module layout, and coding conventions, see [`AGENTS.md`](AGENTS.md) — the authoritative reference.

## Prerequisites

- **JDK 25+** for the CLI and server.
- **JetBrains Runtime (JBR) 25** for the desktop app.
- Use `./gradlew` (or `gradlew.bat` on Windows); native access is already wired into the Gradle tasks.

## Project layout

Four Gradle modules, with `cli`, `composeApp`, and `server` all depending on `shared`:

- `shared` — multiplatform domain + JVM data layer (DSS-backed implementations).
- `cli` — Clikt command-line app.
- `composeApp` — Compose Multiplatform desktop (JVM) and web (Wasm).
- `server` — Ktor HTTP server.

## Building and testing

Run the tests for the module you touched before opening a PR:

```
./gradlew :shared:jvmTest
./gradlew :cli:test
./gradlew :server:test
./gradlew :composeApp:jvmTest
```

Running the apps locally:

```
./gradlew :cli:run --args="--help"
./gradlew :composeApp:run
./gradlew :server:run
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

## Commit messages — Conventional Commits (required)

Release notes are generated automatically from commit messages, so every commit **must** follow [Conventional Commits](https://www.conventionalcommits.org):

```
type(scope): short summary
```

- **Shown in release notes:** `feat`, `fix`, `perf`, `revert`.
- **Hidden from release notes** (still welcome): `docs`, `test`, `refactor`, `chore`, `build`, `ci`, `style`.
- **Breaking changes:** append `!` (`feat(cli)!: ...`) or add a `BREAKING CHANGE:` footer — always highlighted.
- **Scope** is optional, lowercase, naming the area you touched (`pkcs11`, `dss`, `cli`, etc.).

Two consequences of how the changelog works:

1. **Each commit shows up individually** in the notes (PRs are merged preserving their commits), so make one clean conventional commit per logical change rather than a single large one.
2. **Releases are per component** (CLI, desktop, server, web), and a commit is attributed to a component by the **files it changes** — anything touching `shared/` or `gradle/` appears in *every* component's notes. Keep each commit focused and confined to the module it belongs to.

## Pull requests

1. Branch off `main`.
2. Open a PR against `main`. CI (build and tests for the affected components) must pass, and the PR needs one approving review.
3. Keep PRs focused, and reference any related issue.

## Code style

Full rules live in [`AGENTS.md`](AGENTS.md); the load-bearing ones:

- **Errors:** return `OperationResult<T>` (Arrow `Either`) via `.left()` / `.right()`; don't throw for expected failures.
- **Dependency injection is Koin-only.**
- **KDoc on every class, interface, and function;** no inline `//` comments.
- **One top-level declaration per file,** named after it.
- **Prefer Kotlin APIs** (`kotlin.uuid.Uuid`, `kotlin.time.*`, `kotlin.io.path.*`) over Java equivalents; isolate Java/DSS bridging in `jvmMain`.
- Wrap credentials and PINs in **`Sensitive<T>`**.
