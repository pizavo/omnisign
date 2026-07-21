# OmniSign — Compose Multiplatform UI

Graphical interface for **OmniSign** — a multiplatform digital signature tool built on the
[EU Digital Signature Service (DSS)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html).
This module provides both the **desktop** (JVM) and **web** (Wasm) targets from a single
Compose Multiplatform codebase. The desktop build runs the DSS engine locally; the web build is a
browser client of the [OmniSign server](../server/README.md), driving signing and validation over
its HTTP API behind an OIDC sign-in.

## Features

| Feature                                          | Desktop (JVM) |   Web (Wasm)   |
|--------------------------------------------------|:-------------:|:--------------:|
| Open and view PDF documents                      |       ✅       |       ✅        |
| Page navigation and zoom                         |       ✅       |       ✅        |
| Sign PDFs (PAdES B/B-T/B-LT/B-LTA)               |       ✅       | via server ¹  |
| Validate signed PDFs (PAdES B–LTA)               |       ✅       | via server ¹  |
| Extend signatures (timestamp / archival)         |       ✅       | via server ¹  |
| Export validation report (JSON/XML/HTML)         |       ✅       |       ✅        |
| Automatic renewal job offers after LTA signing   |       ✅       |       —        |
| Configuration profiles                           |   ✅ (CRUD)    | select only ² |
| Global settings (algorithms, services, tokens)   |       ✅       | read-only ²   |
| Trusted certificate management                   |       ✅       | read-only ²   |
| Config backup archive                            | export/import |  export only  |
| Custom Trusted List builder (ETSI TS 119 612)    |       ✅       |       —        |
| OS-level archival renewal scheduler              |       ✅       |       —        |
| PKCS#11 hardware token / smart card support      |       ✅       |       —        |
| File-based certificate loading (PKCS#12, JKS)    |       ✅       |       —        |
| PDF file associations (open with OmniSign)       |       ✅       |       —        |
| SSO sign-in (OIDC)                               |       —       |       ✅        |
| Language / region switch (English/Czech/Slovak)  |       ✅       |       ✅        |
| Dark / light theme toggle                        |       ✅       |       ✅        |
| JBR custom title bar (native frame)              |       ✅       |       —        |
| Help panel                                       |       ✅       |       ✅        |

¹ Performed by the OmniSign **server** the web client is connected to — requires signing in, and is
  offered only for the operations that server permits (reported via its capabilities endpoint).
² The web client reads profiles, settings, and trusted certificates from the server for display and
  can select the active profile, but cannot edit configuration.

## Prerequisites

- **JDK 25+** — required by the `shared` module.
- **JetBrains Runtime (JBR) 25** — required for the desktop target. The build will fail with a
  descriptive error if JBR is not found. Install it via:
  - IntelliJ IDEA → Settings → Build → Build Tools → Gradle → Gradle JDK
  - Download from [JetBrains Runtime releases](https://github.com/JetBrains/JetBrainsRuntime/releases)
    and place it under `~/.jdks/` so Gradle auto-detects it.

## Building & Running

### Desktop

```shell
# Linux / macOS
./gradlew :composeApp:run

# Windows
.\gradlew.bat :composeApp:run
```

The desktop target also accepts a `renew` argument for headless archival renewal triggered by the
OS scheduler:

```shell
.\gradlew.bat :composeApp:run --args="renew"
```

### Web (Wasm) — development server

```shell
# Linux / macOS
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Windows
.\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun
```

A local development server starts and opens the app in the default browser.

The web target is a *client* of the OmniSign server, so it needs one to talk to. Point the build at
a running server with the `OMNISIGN_SERVER_URL` environment variable, which is baked into
`BuildConfig.SERVER_URL`; leaving it unset means "same origin", which is correct when the server
hosts both the bundle and the `/api/v1` routes. Without a reachable server the app still opens and
renders PDFs, but `CapabilitiesViewModel` hides every server-backed operation.

### Web (Wasm) — production build

```shell
# Linux / macOS
./gradlew :composeApp:wasmJsBrowserDistribution

# Windows
.\gradlew.bat :composeApp:wasmJsBrowserDistribution
```

The production bundle is written to `composeApp/build/dist/wasmJs/productionExecutable/`. An operator
can retarget a pre-built bundle at a different server by serving a `web-config.json` next to it —
it is read at startup and takes precedence over the compiled-in `SERVER_URL`, so no recompile is
needed. Browser support requires WebAssembly GC: Chrome/Edge 119+, Firefox 120+, or Safari 18.2+.

## Native Distribution (Desktop)

The Compose Desktop Gradle plugin packages the application into platform-native installers.

```shell
# Build all configured formats for the current OS
.\gradlew.bat :composeApp:packageDistributionForCurrentOS
```

### Supported formats

| OS      | Formats              |
|---------|----------------------|
| Windows | MSI, EXE             |
| macOS   | DMG, PKG             |
| Linux   | DEB, RPM, AppImage   |

Individual formats can be built with dedicated tasks:

```shell
.\gradlew.bat :composeApp:packageMsi
.\gradlew.bat :composeApp:packageExe
./gradlew :composeApp:packageDmg
./gradlew :composeApp:packageDeb
./gradlew :composeApp:packageRpm
./gradlew :composeApp:packageAppImage
```

### macOS Gatekeeper note

The macOS packages are currently unsigned. On first launch macOS may show an
"unidentified developer" warning. Bypass it with:

```shell
xattr -cr /Applications/OmniSign.app
```

Or by right-clicking the application and choosing **Open**.

## Testing

```shell
# JVM-target tests (ViewModels, UI state models, platform implementations)
.\gradlew.bat :composeApp:jvmTest

# Common-target tests (shared UI logic)
.\gradlew.bat :composeApp:allTests
```

Tests use **Kotest 6** (FunSpec style), **MockK**, and **Arrow Kotest matchers**.
ViewModel tests use `StandardTestDispatcher` + `runTest` with `Dispatchers.setMain`/`resetMain`,
injecting mocked use cases directly (no Koin).

## Architecture

### Source set layout

```
composeApp/src/
├── commonMain/          Shared Compose UI — layouts, ViewModels, state models, platform expect declarations
│   └── kotlin/cz/pizavo/omnisign/
│       ├── App.kt                   Root composable (theme + IslandLayout)
│       ├── lumo/                    Lumo design-system theme, colors, typography, components
│       │   ├── Color.kt, Theme.kt, Typography.kt
│       │   ├── components/          Accordion, AlertDialog, Badge, Button, Card, Checkbox,
│       │   │                        Chip, Dialog, Divider, Icon, IconButton, NavigationBar,
│       │   │                        OtpTextField, ProgressIndicators, RadioButton, Scaffold,
│       │   │                        SelectableContent, Snackbar, Surface, Switch, Text, TextField,
│       │   │                        Tooltip, TopBar, TriStateToggle
│       │   └── foundation/          ButtonElevation, Elevation, Providers, Ripple, SystemBarsDefaultInsets
│       └── ui/
│           ├── layout/              Island shell: toolbar, sidebar, side panel, content card,
│           │                        signing / timestamp / settings / TL-builder / password /
│           │                        certificate-details / PKCS#11-diagnostic dialogs, profile edit
│           │                        panel, trusted certs panel, export report menu, renewal job offer
│           ├── model/               UI state data classes and enums (PdfViewerState, SidePanel,
│           │                        SigningDialogState, TimestampDialogState, TlBuilderDialogState,
│           │                        ProfileEditState, GlobalConfigEditState, SettingsCategory,
│           │                        ServerCapabilities, RenewalJobOfferState, TrustedCertsPanelState, …)
│           ├── platform/            expect declarations (PdfPageRenderer, PdfPathLoader,
│           │                        PdfFilePicker, FileExporter, SaveDialog, SaveDocument,
│           │                        CertificateFileReader, ConfigArchiveFiles, SupportLog,
│           │                        UiPreferencesStore, PlatformInfo, PlatformCursors, LocalAppLocale, …)
│           └── viewmodel/           MVVM ViewModels (PdfViewerViewModel, SignatureViewModel,
│                                    SigningViewModel, TimestampViewModel, ProfileViewModel,
│                                    SettingsViewModel, TlBuilderViewModel, TrustedCertsViewModel,
│                                    CapabilitiesViewModel) plus plain helpers RenewalJobAssigner,
│                                    TrustedCertStaging
│
├── jvmMain/             Desktop-specific implementations
│   └── kotlin/cz/pizavo/omnisign/
│       ├── main.kt                  JVM entry point — Koin bootstrap, JBR decorated window,
│       │                            headless `renew` mode for OS-scheduled archival
│       └── ui/platform/             actual implementations (PDFBox renderer, ComposePasswordCallback,
│                                    JbrTitleBarHelper, WindowStateStore, AWT file exporter, cursors)
│
├── webMain/             Wasm-specific implementations (server-backed client)
│   └── kotlin/cz/pizavo/omnisign/
│       ├── main.kt                  Wasm entry point — MuPDF init, server-URL resolve, Koin
│       │                            bootstrap, OIDC auth gate, then ComposeViewport (App / LoginScreen)
│       ├── web/                     Server-URL resolver + OIDC login (LoginScreen, WebAuthGate, PKCE)
│       └── ui/platform/             actual implementations (MuPDF renderer, browser file
│                                    export/download, preferences) — one per commonMain expect
│
├── jvmTest/             Desktop-target tests (ViewModel tests, UI state model tests)
└── commonTest/          Shared UI tests
```

### Key design decisions

- **Island layout** — The desktop shell is inspired by the IntelliJ "Island" UI: a seamless toolbar
  at the top, collapsible side panels on both edges, and a central content card. Panel visibility
  and width are managed with local `remember` state. Four side panels are available: **Signature**
  (left), **Profiles** (right), **Trusted Certificates** (right), and **Help** (right, pinned to
   the bottom).
- **JBR Custom Title Bar** — On desktop, `JbrTitleBarHelper` removes the OS title bar while keeping
  the window **decorated** (native shadows, snap assist, resize borders). Compose renders its own
  toolbar in the freed space; native window-control buttons (minimize, maximize, close) are
  provided by JBR and accounted for via `LocalTitleBarRightInset`.
- **MVVM** — Nine ViewModels expose `StateFlow`-based state consumed by composables via
  `collectAsState()`. `IslandLayout` constructs each one inside `remember { … }`, resolving its
  dependencies from Koin via `KoinPlatform.getKoinOrNull()` and falling back to a `null` ViewModel
  when a definition is absent — which is how the web target copes with ports it cannot bind
  (`koinViewModel()` is not used). Shared cross-ViewModel logic
  (e.g., renewal job offers) is factored into plain classes like `RenewalJobAssigner`.
- **Sealed UI states** — Dialogs use sealed interfaces (`SigningDialogState`,
  `TimestampDialogState`, `TlBuilderDialogState`) so that the Compose layer can pattern-match on
  the current phase (Idle → Loading → Ready → InProgress → Success / Error) and render the
  appropriate content.
- **Platform abstraction** — `expect`/`actual` declarations in `ui/platform/` isolate
  platform-specific concerns (PDF rendering via PDFBox on JVM, file export, cursor shapes,
  certificate file reading, executable path resolution, password dialogs, theme persistence).
  The web target provides its own `actual` implementations without JVM dependencies.
- **Lumo design system** — A custom theme layer (`lumo/`) supplies colors, typography, and a
  component library (buttons, cards, tooltips, text fields, switches, accordions, snackbars, …)
  used across all composables.
- **Settings dialog** — A categorized settings dialog with left navigation organizes configuration
  into groups: Signing (defaults, disabled algorithms), Services (TSP, OCSP/CRL), Validation
  (policy, algorithm constraints, trusted certificates, custom trusted lists), Archiving (renewal
  jobs, OS scheduler), Tokens (PKCS#11 libraries), Backup (export/import the full configuration
  archive), Appearance (Linux window title-bar mode), and Language & Region (UI language and date
  format).
- **Web target is a server-backed client** — The Wasm build is a thin client of the OmniSign
  server, not a local engine. `webMain/main.kt` reads the server's capabilities, gates the whole UI
  behind an OIDC sign-in when the server requires authentication, and `CapabilitiesViewModel` hides
  any operation the server does not permit. Signing, validation, and timestamping run on the server
  over HTTP (the `Remote*Repository` implementations in `shared/wasmJsMain`); only PDF rendering is
  local, via MuPDF WebAssembly.
- **Internationalization** — UI strings ship in English, Czech, and Slovak
  (`composeResources/values*`) and switch live, alongside the date format, from the **Language &
  Region** settings. The web client forwards the chosen language as an `Accept-Language` header so
  the server localizes its DSS validation report to match.

### Dependency on `shared`

The `composeApp` module depends on `shared` for domain models, use cases, repositories, and DI
wiring, and runs the *same* use cases on both targets — only the bound repositories differ. On the
JVM target, `jvmMain/main.kt` bootstraps Koin with `appModule` (common use cases) and
`jvmRepositoryModule` (DSS-backed implementations). On the web target, `webMain/main.kt` bootstraps
Koin with `appModule` and `webDataModule`, which binds HTTP-backed `Remote*` implementations
(in `shared/wasmJsMain`) that call the OmniSign server. Operations a browser cannot
perform locally — local-keystore signing, PKCS#11, editing the server-owned configuration — return
a `Left` or are hidden, so the shared use cases and ViewModels run unchanged against the remote
repositories.

## Key Libraries

| Library                  | Purpose                                        |
|--------------------------|------------------------------------------------|
| Compose Multiplatform    | Shared declarative UI for JVM and Wasm         |
| Lumo                     | Custom design-system theme and components      |
| Koin + Koin-Compose      | Dependency injection (`KoinPlatform.getKoin()`) |
| Lifecycle ViewModel      | MVVM architecture for Compose                  |
| FileKit                  | Cross-platform file picker & browser downloads |
| Apache PDFBox            | PDF page rendering (desktop / JVM)             |
| MuPDF (WebAssembly)      | PDF page rendering (web / Wasm)                |
| Ktor client              | Calls to the OmniSign server API (web target)  |
| JBR API                  | Custom title bar on JetBrains Runtime          |
| kotlin-logging + Logback | Multiplatform logging facade (JVM backend)     |
| Kotest + MockK + Arrow   | Testing framework with Either matchers         |

