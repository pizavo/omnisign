# OmniSign — Compose Multiplatform UI

Graphical interface for **OmniSign** — a multiplatform digital signature tool built on the
[EU Digital Signature Service (DSS)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html).
This module provides both the **desktop** (JVM) and **web** (Wasm) targets from a single
Compose Multiplatform codebase.

## Features

| Feature                                        | Desktop (JVM) | Web (Wasm) |
|------------------------------------------------|:-------------:|:----------:|
| Open and view PDF documents                    |       ✅       |     ✅      |
| Page navigation and zoom                       |       ✅       |     ✅      |
| Sign PDFs (PAdES B/B-T/B-LT/B-LTA)             |       ✅       |     —      |
| Validate signed PDFs (PAdES B–LTA)             |       ✅       |     —      |
| Export validation report (JSON/XML/HTML)       |       ✅       |     —      |
| Extend signatures (timestamp / archival)       |       ✅       |     —      |
| Automatic renewal job offers after LTA signing |       ✅       |     —      |
| Configuration profiles (CRUD)                  |       ✅       |     —      |
| Global settings (algorithms, services, tokens) |       ✅       |     —      |
| Trusted certificate management                 |       ✅       |     —      |
| Custom Trusted List builder (ETSI TS 119 612)  |       ✅       |     —      |
| OS-level archival renewal scheduler            |       ✅       |     —      |
| PKCS#11 hardware token / smart card support    |       ✅       |     —      |
| File-based certificate loading (PKCS#12, JKS)  |       ✅       |     —      |
| PDF file associations (open with OmniSign)     |       ✅       |     —      |
| Dark / light theme toggle                      |       ✅       |     ✅      |
| JBR custom title bar (native frame)            |       ✅       |     —      |
| Help panel                                     |       ✅       |     ✅      |

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

### Web (Wasm) — production build

```shell
# Linux / macOS
./gradlew :composeApp:wasmJsBrowserDistribution

# Windows
.\gradlew.bat :composeApp:wasmJsBrowserDistribution
```

The production bundle is written to `composeApp/build/dist/wasmJs/productionExecutable/`.

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
│       │   │                        Chip, Divider, Icon, IconButton, NavigationBar,
│       │   │                        OtpTextField, ProgressIndicators, RadioButton, Scaffold,
│       │   │                        Snackbar, Surface, Switch, Text, TextField, Tooltip,
│       │   │                        TopBar, TriStateToggle
│       │   └── foundation/          ButtonElevation, Elevation, Providers, Ripple, SystemBarsDefaultInsets
│       └── ui/
│           ├── layout/              Island shell: toolbar, sidebar, side panel, content card,
│           │                        signing dialog, timestamp dialog, settings dialog,
│           │                        TL builder dialog, password dialog, profile edit panel,
│           │                        trusted certs panel, export report menu, renewal job offer
│           ├── model/               UI state data classes and enums (PdfViewerState, SidePanel,
│           │                        SigningDialogState, TimestampDialogState, TlBuilderDialogState,
│           │                        ProfileEditState, GlobalConfigEditState, SettingsCategory,
│           │                        RenewalJobOfferState, TrustedCertsPanelState, …)
│           ├── platform/            expect declarations (PdfPageRenderer, FileExporter,
│           │                        PdfFilePicker, PdfLoader, PdfPathLoader, CertificateFileReader,
│           │                        ExecutablePath, PasswordDialogController, PlatformCursors,
│           │                        PlatformFilePath, ThemePreferenceStore, WindowControls)
│           └── viewmodel/           MVVM ViewModels:
│                                    PdfViewerViewModel, SignatureViewModel, SigningViewModel,
│                                    TimestampViewModel, ProfileViewModel, SettingsViewModel,
│                                    TlBuilderViewModel, TrustedCertsViewModel, RenewalJobAssigner
│
├── jvmMain/             Desktop-specific implementations
│   └── kotlin/cz/pizavo/omnisign/
│       ├── main.kt                  JVM entry point — Koin bootstrap, JBR decorated window,
│       │                            headless `renew` mode for OS-scheduled archival
│       └── ui/platform/             actual implementations (PDFBox renderer, ComposePasswordCallback,
│                                    JbrTitleBarHelper, WindowStateStore, AWT file exporter, cursors)
│
├── webMain/             Wasm-specific implementations
│   └── kotlin/cz/pizavo/omnisign/
│       ├── main.kt                  Wasm entry point — ComposeViewport
│       └── ui/platform/             actual implementations (browser-backed renderer, file export)
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
  `collectAsState()`. Dependencies are resolved through Koin. Shared cross-ViewModel logic
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
- **Settings dialog** — A categorized settings dialog with left navigation organizes global
  configuration into groups: Signing (defaults, disabled algorithms), Services (TSP, OCSP/CRL),
  Validation (policy, algorithm constraints, trusted certificates, custom trusted lists),
  Archiving (renewal jobs, OS scheduler), and Tokens (PKCS#11 libraries).

### Dependency on `shared`

The `composeApp` module depends on `shared` for domain models, use cases, repositories, and DI
wiring. On the JVM target, `jvmMain/main.kt` bootstraps Koin with both `appModule` (common use
cases) and `jvmRepositoryModule` (DSS-backed implementations). The Wasm target cannot access
JVM-only modules — ViewModels that depend on DSS use cases gracefully degrade to `null` via
`KoinPlatform.getKoinOrNull()`.

## Key Libraries

| Library                  | Purpose                                        |
|--------------------------|------------------------------------------------|
| Compose Multiplatform    | Shared declarative UI for JVM and Wasm         |
| Lumo                     | Custom design-system theme and components      |
| Koin + Koin-Compose      | Dependency injection with `koinViewModel()`    |
| Lifecycle ViewModel      | MVVM architecture for Compose                  |
| FileKit                  | Cross-platform file picker dialogs             |
| Apache PDFBox            | PDF page rendering (JVM only)                  |
| JBR API                  | Custom title bar on JetBrains Runtime          |
| kotlin-logging + Logback | Multiplatform logging facade (JVM backend)     |
| Kotest + MockK + Arrow   | Testing framework with Either matchers         |

