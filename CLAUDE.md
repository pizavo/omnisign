# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The authoritative agent-facing reference is **`AGENTS.md`** at the repo root — read it first for the full architecture, module layout, key patterns, and coding conventions. This file is a quick orientation that pulls out the most load-bearing points.

## Project

OmniSign — Kotlin Multiplatform digital signature app (PAdES BASELINE B/B-T/B-LT/B-LTA) on the EU DSS 6.3 library. Diploma thesis project; documentation quality matters.

## Modules

Four Gradle modules with strict dependency direction `cli` / `composeApp` / `server` → `shared`:

- `shared` — multiplatform domain + JVM data layer (clean architecture). `commonMain` is platform-agnostic and **must not import DSS**; DSS-backed implementations live in `jvmMain` (`Dss*Repository`, serializers, OS schedulers, PKCS#11, etc.). Domain↔DSS bridging is via Kotlin extension functions in `jvmMain/.../enums/*Extension.kt`.
- `cli` — Clikt CLI, fat JAR via Shadow. Entry: `cz.pizavo.omnisign.CliKt`.
- `composeApp` — Compose Multiplatform desktop (JVM) + web (Wasm) from one codebase. MVVM with Koin-Compose ViewModels exposing `StateFlow`. `expect`/`actual` split for platform concerns. Web target gracefully degrades for DSS-only use cases via `KoinPlatform.getKoinOrNull()`. Entry (desktop): `cz.pizavo.omnisign.MainKt`.
- `server` — Ktor/Netty HTTP server. Routes mounted under `/api/v1`. SSO via OIDC (with provider presets) or trusted header injection; JWT (HS*) sessions. Allowed operations gated by `AllowedOperation` (`SIGN` is opt-in). Entry: `cz.pizavo.omnisign.ApplicationKt`.

`docs/` is a Docusaurus site on pnpm, requiring Node 26+ (`pnpm start` inside `docs/`). Five doc sections: `docs-cli`, `docs-desktop`, `docs-server`, `docs-web`, `docs-development`.

## Build & Run

Requires **JDK 25+**; desktop target additionally requires **JBR 25**. `--enable-native-access=ALL-UNNAMED` is wired in for all JVM tasks. Use `gradlew.bat` on this Windows host.

```powershell
.\gradlew.bat :cli:shadowJar                          # CLI fat JAR → cli/build/libs/omnisign-*.jar
.\gradlew.bat :cli:run --args="--help"                 # Run CLI directly
.\gradlew.bat :cli:jpackage                            # Native installer (MSI/DEB/RPM/DMG)
.\gradlew.bat :composeApp:run                          # Desktop app (needs JBR 25)
.\gradlew.bat :server:run                              # Ktor server
.\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun  # Web (Wasm)
.\gradlew.bat :dokkaGenerate                           # Aggregated KDoc → build/dokka/html/
.\gradlew.bat :shared:updateLotlKeystore               # Refresh bundled EU LOTL keystore
```

## Tests

Kotest 6 (FunSpec) + MockK + Arrow Kotest matchers (`shouldBeLeft()` / `shouldBeRight()`). Tests live in `src/jvmTest` (shared, composeApp) and `src/test` (cli, server). JVM test tasks auto-add `-XX:+EnableDynamicAgentLoading -Xshare:off --enable-native-access=ALL-UNNAMED`; the Decoroutinator plugin gives readable coroutine stack traces.

```powershell
.\gradlew.bat :shared:jvmTest
.\gradlew.bat :cli:test
.\gradlew.bat :server:test
.\gradlew.bat :composeApp:jvmTest

# Single test class / method:
.\gradlew.bat :shared:jvmTest --tests "cz.pizavo.omnisign.SomeTest"
.\gradlew.bat :cli:test --tests "cz.pizavo.omnisign.commands.SignTest.signs PDF*"
```

CLI tests use the Kotest `KoinExtension` with `KoinLifecycleMode.Test` (see `cli/src/test/.../commands/SignTest.kt`). composeApp ViewModel tests use `StandardTestDispatcher` + `runTest` with `Dispatchers.setMain`/`resetMain` and inject mocks directly without Koin (see `composeApp/src/jvmTest/.../ui/viewmodel/SigningViewModelTest.kt`).

## Project-specific conventions (read carefully)

- **Errors**: `OperationResult<T>` is `Arrow Either<OperationError, T>`. Sealed error interfaces under `domain/model/error/`. Use `.left()` / `.right()` — do not throw for expected failures.
- **DI is Koin-only**. Register services, repositories, use cases, and helpers as Koin definitions. Kotlin `object` is allowed only for Compose `*Defaults`, theme accessors (`LumoTheme`), `expect`/`actual` compile-time utilities, and `companion object` blocks (constants, loggers, factories). Wiring: `shared/commonMain/.../di/AppModule.kt` (use cases), `shared/jvmMain/.../di/JvmModule.kt` (JVM repositories). Each UI entry point bootstraps Koin and provides its own `PasswordCallback`.
- **Prefer Kotlin APIs over Java**: `kotlin.uuid.Uuid` not `java.util.UUID`; `kotlin.time.Instant` / `kotlin.time.Duration` not `java.time.*`; `kotlin.io.path.*` over raw `java.nio.file.*`. Drop to Java only when bridging a Java library (e.g., DSS) and isolate the bridge in `jvmMain`.
- **One top-level declaration per file.** No two top-level data classes/enums/objects in one `.kt`. Extension functions for that declaration may live alongside it. Name the file after the declaration.
- **KDoc on every class, interface, and function.** Do **not** add inline comments unless asked. Do **not** create new Markdown files unless asked.
- **`Sensitive<T>`** (a `data class` in `domain/model/value/` — deliberately *not* a `@JvmInline value class`; see its KDoc) wraps credentials: its `toString()` returns `***` and serialization is intentionally blocked. Use it for any password/PIN-like value.
- **Config resolution** is a three-layer merge (global → profile → operation overrides) in `ResolvedConfig.resolve()`. Persisted as JSON under `~/.config/omnisign/` (Linux), `~/Library/Application Support/omnisign/` (macOS), `%APPDATA%/omnisign/` (Windows).
- **DSS infrastructure**: `DssServiceFactory` centralizes TSP sources, certificate verifiers, and TL validation jobs — injected into all `Dss*Repository`s. DSS warnings flow through `CollectingStatusAlert` + `DssLogCapture` (Logback appender on `eu.europa.esig`) → `DssWarningSanitizer` → `WarningCategory` summaries; TSP failures go through `TspErrorDetector` for RFC 3161 `PKIFailureInfo` codes.
- **`PasswordCallback`** is the platform boundary: CLI uses terminal input, desktop uses a dialog (`ComposePasswordCallback`), server returns null/error.
- **JVM-only use cases**: `ExportImportConfigUseCase` (Jackson; class lives in `commonMain`), plus `ConfigArchiveUseCase` (ZIP archives), `RenewBatchUseCase` (DSS-backed archiving), and `MigrateTrustedCertificatesUseCase` (all three in `jvmMain`) are wired in `jvmRepositoryModule`, not `appModule`.

When any of these conventions feel ambiguous in context, consult `AGENTS.md` — it has the long-form rationale and edge cases.
