# AGENTS.md

## Project Overview

OmniSign is a Kotlin Multiplatform digital signature app (PAdES BASELINE B/B-T/B-LT/B-LTA) built on the EU DSS 6.3 library. It is a diploma thesis project — treat documentation quality and SOLID principles seriously.

## Architecture

Four Gradle modules with a strict dependency direction: `cli`/`composeApp`/`server` → `shared`.

| Module       | Role                                             | Entry point                        |
|--------------|--------------------------------------------------|------------------------------------|
| `shared`     | Multiplatform core (domain + JVM data layer)     | —                                  |
| `cli`        | Clikt-based CLI (fat JAR via Shadow)             | `cz.pizavo.omnisign.CliKt`         |
| `composeApp` | Compose Multiplatform desktop (JVM) + web (Wasm) | `cz.pizavo.omnisign.MainKt`        |
| `server`     | Ktor HTTP server                                 | `cz.pizavo.omnisign.ApplicationKt` |
| `docs`       | Docusaurus user documentation site               | `npm start` inside `docs/`         |

Aggregated Dokka API docs are generated via `.\gradlew.bat :dokkaGenerate` → `build/dokka/html/`.

### shared module layout (clean-architecture)

- `commonMain` — platform-agnostic domain: models, use cases, repository/port interfaces, error types, `platform/PasswordCallback.kt`, `Constants.kt`. **No DSS imports here.**
  - `domain/model/config/` — `AppConfig`, `GlobalConfig`, `ProfileConfig`, `OperationConfig`, `ResolvedConfig`, `AlgorithmConstraintsConfig`, `SchedulerConfig`, `RenewalJob`, `TrustedCertificateConfig`, `CustomTrustedListConfig`, `EtsiConstants`, `service/` (CRL/OCSP/TSP configs), `enums/` (domain enums with `dssName`).
  - `domain/model/parameters/` — `SigningParameters`, `ValidationParameters`, `ArchivingParameters`, `VisibleSignatureParameters`.
  - `domain/model/result/` — `OperationResult`, `SigningResult`, `ArchivingResult`, `AnnotatedWarning`, `RenewBatchResult`, `DocumentTimestampInfo`.
  - `domain/model/validation/` — `ValidationReport`, `ValidationResult`, `SignatureValidationResult`, `TimestampValidationResult`, `SignatureTrustTier`, `ReportExportFormat`, `json/` (JSON report serialization).
  - `domain/model/signature/` — `CertificateInfo`, `Signature`, `TimestampInfo`.
  - `domain/model/value/` — `Sensitive<T>`, `InstantFormatter` (multiplatform date formatting).
  - `domain/model/error/` — `OperationError`, `SigningError`, `ValidationError`, `ArchivingError`, `ConfigurationError` (sealed interfaces).
  - `domain/service/` — `AlgorithmExpirationChecker`, `CredentialStore`, `TokenService`, `SigningToken`, `TokenInfo`, `CertificateEntry`.
  - `domain/port/` — `ConfigSerializer`, `ConfigSerializerRegistry`, `SchedulerPort`, `TrustedListCompilerPort`.
  - `domain/repository/` — `SigningRepository`, `ValidationRepository`, `ArchivingRepository`, `ConfigRepository`, `AvailableCertificateInfo`, `CertificateDiscoveryResult`.
  - `domain/usecase/` — 15 use cases (e.g. `SignDocumentUseCase`, `ValidateDocumentUseCase`, `ManageTrustedListsUseCase`). All registered in `di/AppModule.kt` except JVM-only ones.
- `jvmMain` — DSS-backed implementations: `data/repository/Dss*Repository.kt`, `data/service/*` (OS schedulers, PKCS#11 discovery, notifications, credential store, trusted list compiler, self-executable resolver, trusted certificate reader), `data/serializer/` (Jackson-based JSON/YAML/XML config serializers), `data/util/DateExtensions.kt` (`java.util.Date` → `kotlin.time.Instant` bridging), `ades/policy/`, DI wiring (`di/JvmModule.kt`).
- DSS ↔ domain bridging uses Kotlin extension functions in `jvmMain/domain/model/config/enums/*Extension.kt` (e.g. `SignatureLevel.toDss()`). `HashAlgorithm` is the exception — it uses a `dssName` property directly in `commonMain` because the mapping is a simple string.

### composeApp module layout (MVVM)

The `composeApp` module delivers both the desktop (JVM) and web (Wasm) targets from a single codebase using three source sets:

- `commonMain` — shared Compose UI: `App.kt` root composable, `lumo/` design system (theme, colors, typography, custom components), `ui/layout/` (Island shell composables, dialogs), `ui/viewmodel/` (MVVM ViewModels exposing `StateFlow`), `ui/model/` (UI state data classes), `ui/platform/` (`expect` declarations for platform-specific concerns like PDF rendering, file export, cursors, theme persistence, window controls via CompositionLocals).
- `jvmMain` — desktop-specific `actual` implementations: PDFBox-based PDF renderer, `ComposePasswordCallback` (dialog-backed `PasswordCallback` + `PasswordDialogController`), JBR Custom Title Bar helper, AWT-based file exporter, `WindowStateStore` for window position persistence, `ThemePreferenceStore` (Java Preferences). Entry point in `main.kt` bootstraps Koin with `appModule` + `jvmRepositoryModule` + a `PasswordCallback` binding. Also supports headless `renew` argument for OS-scheduled archival renewal.
- `webMain` — Wasm-specific `actual` implementations: browser-backed PDF renderer, file export, theme persistence. Entry point uses `ComposeViewport`. Wasm cannot access JVM-only DSS modules — ViewModels that depend on DSS use cases gracefully degrade via `KoinPlatform.getKoinOrNull()`.

Key libraries: **Lumo** (design system plugin), **Koin-Compose** + **Lifecycle ViewModel** (DI-aware MVVM), **FileKit** (cross-platform file picker), **Apache PDFBox** (PDF rendering, JVM only), **JBR API** (custom title bar, JVM only), **kotlin-logging** (multiplatform logging facade, backed by Logback on JVM).

Desktop requires **JetBrains Runtime (JBR) 25** — the build fails with a descriptive error if JBR is not found. Install via IntelliJ IDEA Gradle JDK settings or download from [JBR releases](https://github.com/JetBrains/JetBrainsRuntime/releases) into `~/.jdks/`.

### server module layout

The `server` module is a Ktor/Netty HTTP server backed by the `shared` JVM data layer.

- `Application.kt` — entry point; loads `ServerConfig` via `ServerConfigLoader`, starts a Netty embedded server with an optional TLS connector (TLS 1.2/1.3, HTTP/2 ALPN). `moduleWith()` installs all plugins and Koin modules in order. `resolveExternalUrl()` derives the public base URL from the `OMNISIGN_EXTERNAL_URL` environment variable or from the server config (used to build OAuth2 redirect URIs).
- `config/` — all server-side configuration data classes:
  - `ServerConfig` — root config (host, port, TLS, CORS, auth, allowed operations, file size limit, etc.).
  - `AuthConfig` — SSO block (`enabled`, `providers`, `session`). When `null` or `enabled = false`, no JWT guard is applied to API routes.
  - `SsoProviderConfig` — sealed hierarchy: `OidcProviderConfig` (OIDC/OAuth2 authorization-code flow with optional `allowedEmailDomains` and `requiredClaims` filters) and `HeaderInjectionProviderConfig` (Shibboleth/SAML 2.0 via trusted reverse-proxy headers).
  - `SsoProviderPreset` — built-in OIDC presets (`GOOGLE`, `MICROSOFT`, `AMAZON_COGNITO`, `KEYCLOAK`, `GITHUB`, `GITLAB`, `AUTH0`, `APPLE`, `EDUID_CZ`) that supply default discovery URLs and scope lists.
  - `SessionConfig` — JWT settings (algorithm, secret, issuer, audience, expiry).
  - `JwtAlgorithmType` — symmetric HMAC variants (`HS256`, `HS384`, `HS512`, default `HS512`); asymmetric RS*/ES* entries are defined as future extension points but are not yet implemented.
  - `AllowedOperation` — `SIGN`, `VALIDATE`, `TIMESTAMP`. Defaults to `{VALIDATE, TIMESTAMP}`; `SIGN` is opt-in for institutional HSM deployments.
  - `TlsConfig`, `HstsConfig`, `CorsConfig`, `CompressionConfig`, `RateLimitConfig` — security and transport configurations. Config is loaded from `server.yml` via Jackson + `SsoProviderConfigDeserializer` (polymorphic deserializer for the sealed `SsoProviderConfig` hierarchy).
- `auth/` — authentication domain:
  - `AuthenticatedPrincipal` — Ktor `Principal` holding `userId`, `email`, `displayName`, `providerName`; embedded as JWT claims.
  - `JwtSessionService` — issues and verifies HMAC-signed JWTs (Auth0 java-jwt library). Secret resolution order: `session.secret` → `OMNISIGN_JWT_SECRET` env var → auto-generated ephemeral value in dev mode → startup failure in production.
  - `OidcDiscoveryService` — fetches OIDC discovery documents; hard-codes GitHub's authorization and token URLs for the `GITHUB` preset.
  - `OidcUserInfoService` — calls the IdP's userinfo endpoint with the access token and maps raw JSON claims to `AuthenticatedPrincipal`.
  - `OidcAuthResult` — intermediate result holding both the mapped `AuthenticatedPrincipal` and the raw claim `JsonObject` (used by post-login filters).
  - `EmailDomainFilter` — `isEmailDomainAllowed()` checks the user's email domain against `OidcProviderConfig.allowedEmailDomains`.
  - `OidcClaimsFilter` — `areRequiredClaimsSatisfied()` validates raw IdP claims against `OidcProviderConfig.requiredClaims` (supports single-valued and multi-valued array claims).
  - `ServerPasswordCallback` — `PasswordCallback` implementation that always returns `null` (the server cannot prompt interactively).
- `api/routes/` — one file per route group, all mounted under `/api/v1`:
  - `AuthRoutes` — `/auth/**`: `GET /login` (provider list), `GET /redirect/{name}` (OIDC initiation), `GET /callback/{name}` (OIDC exchange + header-injection), `GET /session`, `POST /refresh`, `POST /logout`.
  - `SigningRoutes`, `ValidationRoutes`, `TimestampRoutes`, `CertificateRoutes`, `ConfigRoutes`, `SystemRoutes` (health + capabilities).
- `api/model/responses/` — response data classes: `ApiError`, `TokenResponse`, `SessionResponse`, `LoginOptionsResponse`, `HealthResponse`, `CapabilitiesResponse`, `SigningResultMeta`, `TimestampResultMeta`, `CertificateListResponse`, `CertificateInfoResponse`, `GlobalConfigResponse`, `ProfileConfigResponse`, `ResolvedConfigResponse`, `TimestampServerSummary`.
- `api/exception/` — `OperationException` (wraps `OperationError` for `StatusPages` handling), `FileTooLargeException`.
- `api/RouteUtils.kt` — shared route helpers (e.g., `rateLimitedIf` extension).
- `plugins/` — one file per Ktor plugin installation: `Authentication`, `Routing`, `Serialization`, `StatusPages`, `CallId`, `CallLogging`, `Compression`, `Cors`, `DefaultHeaders`, `ForwardedHeaders`, `HttpsRedirect`, `RateLimit`, `AutoHeadResponse`.
- `di/ServerModule.kt` — server Koin module; provides `ServerConfig`, `ServerConfigLoader`, `PasswordCallback` (always null), `CoroutineContext` (IO), `HttpClient` (CIO, JSON content-negotiation), `JwtSessionService`, `OidcDiscoveryService`, `OidcUserInfoService`.

Key libraries: **Ktor** (Netty engine, server framework), **Auth0 java-jwt** (JWT signing/verification), **Ktor client CIO** (OIDC discovery and userinfo HTTP calls), **kotlin-logging** (Logback-backed).

### Key patterns

- **Error handling**: `OperationResult<T>` = `Arrow Either<OperationError, T>`. All errors are sealed interfaces under `domain/model/error/`. Use `.left()` / `.right()`, never throw for expected failures.
- **DI**: Koin is the **sole** dependency-injection mechanism — use it everywhere possible. Register all services, repositories, use cases, factories, and helpers as Koin definitions (`single`, `factory`, `scoped`). Kotlin `object` declarations are permitted **only** when Koin is technically impossible or nonsensical (e.g., Compose `*Defaults` objects that supply default parameter values, `LumoTheme` accessor, multiplatform `expect`/`actual` utilities that must be resolved at compile time, or `companion object` blocks holding constants/loggers/factory methods). When adding a new class, default to a Koin-managed definition; justify with a comment in the module file if an `object` is truly required. Common use cases are registered in `shared/commonMain/.../di/AppModule.kt`; JVM repository bindings in `shared/jvmMain/.../di/JvmModule.kt`. Each UI module bootstraps Koin in its `main()` and must provide a `PasswordCallback` implementation.
- **Config resolution**: Three-layer merge (global → profile → operation overrides) in `ResolvedConfig.resolve()`. Config is persisted as JSON via `FileConfigRepository` at `~/.config/omnisign/` (Linux), `~/Library/Application Support/omnisign/` (macOS), or `%APPDATA%/omnisign/` (Windows).
- **DSS infrastructure**: `DssServiceFactory` centralises TSP sources, certificate verifiers, and TL validation jobs — injected into all `Dss*Repository` classes. Run `.\gradlew.bat :shared:updateLotlKeystore` to refresh the bundled EU LOTL keystore when the EC rotates its signing keys.
- **Sensitive values**: `Sensitive<T>` (`domain/model/value/Sensitive.kt`) is an inline value class whose `toString()` returns `***`. Wrap credentials with it to prevent accidental log/debug leaks. Serialization is intentionally blocked.
- **JVM-only use cases**: `ExportImportConfigUseCase` depends on Jackson serializers so it is registered in `jvmRepositoryModule`, not `appModule`. `RenewBatchUseCase` is also JVM-only (located in `jvmMain`) because it orchestrates DSS-backed archiving operations.
- **DSS warning pipeline**: DSS diagnostics are captured via two mechanisms — `CollectingStatusAlert` (wired into the `CertificateVerifier`) and `DssLogCapture` (a Logback appender attached to the `eu.europa.esig` parent logger during operations). Raw warnings are then classified by `DssWarningSanitizer` into `WarningCategory` buckets and emitted as user-friendly summaries. `TspErrorDetector` extracts RFC 3161 `PKIFailureInfo` codes from TSP exceptions.
- **composeApp MVVM**: ViewModels in `composeApp/commonMain` extend `ViewModel` (Lifecycle), expose `StateFlow<UiState>`, and are injected via `koinViewModel()`. Heavy work (signing, certificate discovery) runs on an injected `CoroutineDispatcher`. See `SigningViewModel` for the canonical pattern. Shared cross-ViewModel logic (e.g., renewal job offers) is factored into plain classes like `RenewalJobAssigner`.
- **Server authentication flow**: `configureAuthentication()` registers a `jwt-api` Bearer provider (always installed, even when auth is disabled) and one `oidc-{name}` OAuth2 provider per `OidcProviderConfig`. `HeaderInjectionProviderConfig` providers skip Ktor's OAuth plugin entirely — their identity is read directly from trusted request headers in `AuthRoutes`. `configureRouting()` wraps all operational routes in `authenticate(jwt-api)` when `auth.enabled = true`; health and all `/auth/**` routes remain publicly accessible. Post-login checks (`isEmailDomainAllowed`, `areRequiredClaimsSatisfied`) run in the callback handler after the IdP response is received, before a JWT is issued.
- **Server allowed operations**: `AllowedOperation.SIGN` is opt-in and triggers a startup warning when enabled without `auth.enabled = true`. `VALIDATE` and `TIMESTAMP` are stateless and enabled by default. Each route group checks its corresponding `AllowedOperation` at request time.
- **Server rate limiting**: Two independent token-bucket zones — `auth` (default 20 req/60 s per IP) and `api` (default 200 req/60 s per IP). In `proxyMode`, the real client IP is resolved via `X-Forwarded-For` by the `ForwardedHeaders` plugin before rate limiting evaluates it.

## Build & Run

Requires **JDK 25+**. All JVM tasks need `--enable-native-access=ALL-UNNAMED` (already configured in `gradle.properties` and build scripts).

```powershell
.\gradlew.bat :cli:shadowJar                          # CLI fat JAR → cli/build/libs/omnisign-*.jar
.\gradlew.bat :cli:run --args="--help"                 # Run CLI directly
.\gradlew.bat :cli:jpackage                            # Native installer for current OS (MSI/DEB/RPM/DMG)
.\gradlew.bat :composeApp:run                          # Desktop app (requires JBR 25)
.\gradlew.bat :server:run                              # Ktor server
.\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun  # Web (Wasm)
```

## Testing

Framework: **Kotest 6** (FunSpec style) + **MockK** + **Arrow Kotest matchers** (`shouldBeLeft()` / `shouldBeRight()`). Tests live in `src/jvmTest` (shared, composeApp), `src/test` (cli, server).

```powershell
.\gradlew.bat :shared:jvmTest
.\gradlew.bat :cli:test
.\gradlew.bat :server:test
.\gradlew.bat :composeApp:jvmTest
```

JVM test tasks automatically add `-XX:+EnableDynamicAgentLoading -Xshare:off --enable-native-access=ALL-UNNAMED`. The Decoroutinator plugin is applied for readable coroutine stack traces.

CLI command tests use the Kotest `KoinExtension` with `KoinLifecycleMode.Test` to supply mocked dependencies per spec (see `cli/src/test/.../commands/SignTest.kt` for the pattern).

composeApp ViewModel tests use `StandardTestDispatcher` + `runTest` with `Dispatchers.setMain`/`resetMain`, injecting mocked use cases directly (no Koin). See `composeApp/src/jvmTest/.../ui/viewmodel/SigningViewModelTest.kt` for the pattern.

## Coding Conventions

- Every class, interface, and function **must** have a KDoc comment.
- Do **not** add inline comments unless specifically asked.
- Do **not** generate Markdown files unless specifically asked.
- **One top-level declaration per file.** Each `.kt` file may contain at most one top-level class, interface, enum, `object`, or sealed hierarchy. Nested/inner classes inside that declaration are fine. Extension functions related to the declaration may coexist in the same file. However, no additional standalone type declarations are allowed (e.g., do **not** put a data class and an enum in the same file, or two data classes in the same file). Name the file after the declaration it contains.
- **Prefer Koin for all dependency wiring.** Never introduce a Kotlin `object` singleton for a service, repository, use case, or helper that could be a Koin-managed `single`/`factory`. Kotlin `object` is acceptable only for Compose component defaults (`*Defaults`), theme accessors (`LumoTheme`), compile-time `expect`/`actual` utilities, and `companion object` blocks (constants, loggers, factory methods).
- **Prefer Kotlin APIs over Java ones.** Always use a Kotlin standard library and multiplatform types when an equivalent exists — e.g. `kotlin.uuid.Uuid` instead of `java.util.UUID`, `kotlin.time.Instant` / `kotlin.time.Duration` instead of `java.time.*`, `kotlin.io.path.*` extensions instead of raw `java.nio.file.*` calls. Fall back to a Java API only when no Kotlin equivalent exists or when bridging with a Java library that requires it (e.g., DSS), and isolate that bridge in `jvmMain`.
- Domain enums (in `commonMain`) carry a `dssName` property; JVM extension functions (in `jvmMain`) map them to DSS counterparts.
- Serializable config models use `kotlinx.serialization`. Jackson serializers (`data/serializer/`) handle YAML/XML export/import.
- CLI commands extend `CliktCommand` and obtain dependencies via `KoinComponent.inject()`. Shared options are grouped in `OperationConfigOptions`.
- The `PasswordCallback` interface is the platform boundary — CLI uses terminal input, desktop uses a dialog (`ComposePasswordCallback`), server returns error.

