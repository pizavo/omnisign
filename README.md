<p align="center">
  <img src="assets/icons/omnisign-logo-512.png" alt="OmniSign logo" />
</p>

<h1 align="center">OmniSign</h1>
<p align="center"><strong>Sign, validate, and future-proof your PDF documents — on any platform.</strong></p>

<div align="center">

[![CI — CLI](https://github.com/pizavo/omnisign/actions/workflows/ci-cli.yml/badge.svg)](https://github.com/pizavo/omnisign/actions/workflows/ci-cli.yml)
[![CI — Desktop](https://github.com/pizavo/omnisign/actions/workflows/ci-desktop.yml/badge.svg)](https://github.com/pizavo/omnisign/actions/workflows/ci-desktop.yml)
[![CI — Server](https://github.com/pizavo/omnisign/actions/workflows/ci-server.yml/badge.svg)](https://github.com/pizavo/omnisign/actions/workflows/ci-server.yml)
[![CI — Web](https://github.com/pizavo/omnisign/actions/workflows/ci-web.yml/badge.svg)](https://github.com/pizavo/omnisign/actions/workflows/ci-web.yml)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.10.1-4285F4?logo=jetpackcompose&logoColor=white)](https://github.com/JetBrains/compose-multiplatform)
[![EU DSS](https://img.shields.io/badge/EU_DSS-6.3-003399?logo=europeanunion&logoColor=white)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE.md)
[![Docs](https://img.shields.io/badge/Docs-GitHub_Pages-brightgreen?logo=docusaurus&logoColor=white)](https://pizavo.github.io/omnisign/)

</div>

---

## What Is OmniSign?

OmniSign is a free, open-source application for **digitally signing PDF documents** in a way that
is legally recognized across the European Union and beyond. Whether you need to sign a contract,
validate a diploma, or ensure that archived documents remain trustworthy for decades, OmniSign
has you covered.

Under the hood, OmniSign is powered by the
[EU Digital Signature Service (DSS)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html)
library — the same technology used by EU member states — and fully supports
**PAdES BASELINE B / B-T / B-LT / B-LTA** signature levels, including PDF/A-3b documents.

### Who Is It For?

| Audience                              | Use Case                                                                                                                     |
|---------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| 👩‍🎓 **Students & academics**        | Sign and validate qualification theses, research papers, or assignments.                                                     |
| 🏛️ **Universities & institutions**   | Deploy a self-hosted server to offer signing and validation to employees and students, with automatic long-term archival.    |
| 👨‍💻 **Developers & sysadmins**      | Integrate digital signatures into CI/CD pipelines and scripts using the CLI or server API with machine-readable JSON output. |
| 📄 **Anyone who needs to sign a PDF** | Use the desktop or web app with a friendly graphical interface — no command-line knowledge required.                         |

### How Does It Work?

A digital signature proves **who** signed a document and that **nothing has changed** since.
OmniSign supports four levels of signature strength, each building on the previous one:

| Level                          | What It Adds            | Why It Matters                                                                                                   |
|--------------------------------|-------------------------|------------------------------------------------------------------------------------------------------------------|
| **B** (Basic)                  | Cryptographic signature | Proves authorship and document integrity.                                                                        |
| **B-T** (Timestamp)            | + Trusted timestamp     | Proves the signature existed at a specific point in time, even if the certificate expires later.                 |
| **B-LT** (Long-Term)           | + Revocation data       | Embeds all the information needed to verify the signature offline, long after the certificate or CA goes away.   |
| **B-LTA** (Long-Term Archival) | + Archival timestamp    | Adds a second timestamp that protects everything above. Can be renewed indefinitely for true digital continuity. |

> 💡 **In practice:** Sign at B-T or higher if you want the signature to remain verifiable
> after your certificate expires. Choose B-LTA for documents that must be trustworthy for
> years or decades (theses, legal contracts, archival records).

## Features

|     | Feature                           | What It Does                                                                                                                                                                                                           |
|-----|-----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ✍️  | **Signing**                       | Sign PDF documents using certificates stored in software files (PKCS #12), hardware tokens (PKCS #11, including qualified smart cards), the Windows Certificate Store, or the macOS Keychain.                          |
| ✅   | **Validation**                    | Verify that a signed PDF is authentic and untampered. Checks against the EU Trusted Lists (eIDAS / LOTL), your own custom trusted lists, or standalone certificate chains — with full CRL and OCSP revocation support. |
| 🕑  | **Timestamping**                  | Upgrade an existing signature step by step — from B-B → B-T → B-LT → B-LTA — by adding RFC 3161 timestamps and revocation data.                                                                                        |
| 🗄️ | **Archival (Digital Continuity)** | Keep B-LTA documents valid indefinitely. OmniSign can automatically re-timestamp them before archival timestamps expire, scheduled by your operating system so you never have to think about it.                       |
| 📜  | **Custom Trusted Lists**          | Build and register your own ETSI-compliant Trusted Lists for environments outside the EU trust framework (e.g., university-internal PKI).                                                                              |
| 🔐  | **Configurable Algorithms**       | SHA-256, SHA-384, and SHA-512 out of the box, plus support for Whirlpool, RIPEMD-160, and future post-quantum algorithms. Per-algorithm expiration management follows ETSI TS 119 312.                                 |
| 👤  | **Profiles**                      | Save named configuration profiles for different contexts — personal, corporate, university — and switch between them instantly.                                                                                        |
| 🤖  | **JSON Output**                   | Machine-readable JSON mode makes it easy to script signing and validation into automated workflows.                                                                                                                    |

## Platforms

OmniSign runs everywhere you need it — from a terminal to a full graphical application,
on your machine or on a shared server.

| Platform    | Module                               | Technology             | Description                                                                                                              |
|-------------|--------------------------------------|------------------------|--------------------------------------------------------------------------------------------------------------------------|
| **Desktop** | [`composeApp`](composeApp/README.md) | Compose Multiplatform  | Graphical app for Linux, Windows, and macOS with PDF preview, signing, validation, and a settings UI.                    |
| **Web**     | [`composeApp`](composeApp/README.md) | Compose for Web (Wasm) | Browser-based PDF viewer; signing and validation are planned via the server API.                                         |
| **Server**  | [`server`](server/README.md)         | Ktor (Kotlin/JVM)      | HTTP API for institutional deployments — sign, validate, and archive from any client.                                    |
| **CLI**     | [`cli`](cli/README.md)               | Kotlin/JVM             | Full-featured command line for scripting and power users. Ships as a fat JAR and native installers (MSI, DEB, RPM, PKG). |

## Getting Started

### Desktop app

The simplest way to use OmniSign. Download the installer for your operating system from the
[**Releases**](https://github.com/pizavo/omnisign/releases) page and run it:

| OS      | Installer                   |
|---------|-----------------------------|
| Windows | `.msi` or `.exe`            |
| macOS   | `.dmg` or `.pkg`            |
| Linux   | `.deb`, `.rpm`, or AppImage |

Then follow the [Desktop guide](https://pizavo.github.io/omnisign/desktop/) to sign, validate, and
archive your documents.

### Command line

The CLI ships as native installers (MSI, DEB, RPM, PKG) and a cross-platform fat JAR — ideal for
scripting and automation with machine-readable JSON output. See the
[CLI guide](https://pizavo.github.io/omnisign/cli/) and [`cli/README.md`](cli/README.md).

### Self-hosted server

Institutions can deploy the [server](server/README.md) to offer signing and validation over HTTP to
their users. See the [Server guide](https://pizavo.github.io/omnisign/server/) for configuration,
authentication, and deployment.

## Documentation

| Resource                                       | Location                                                                                           |
|------------------------------------------------|----------------------------------------------------------------------------------------------------|
| **User guides** (Desktop · Web · Server · CLI) | [Documentation site](https://pizavo.github.io/omnisign/) — or run `npm start` inside [`docs/`](docs/) |
| **API reference** (KDoc)                       | Generated via `./gradlew :dokkaGenerate` → `build/dokka/html/`                                      |
| **CLI reference**                              | [`cli/README.md`](cli/README.md)                                                                   |
| **Desktop & Web details**                      | [`composeApp/README.md`](composeApp/README.md)                                                     |
| **Server reference**                           | [`server/README.md`](server/README.md)                                                             |

---

## For Developers

The rest of this document covers building OmniSign from source and the project internals. For the
full architecture, module layout, and coding conventions, see [`AGENTS.md`](AGENTS.md).

### Project Structure

```
omnisign/
├── shared/         Multiplatform core — domain models, use cases, DSS integration
├── cli/            Command-line interface (fat JAR + native installers)
├── composeApp/     Compose Multiplatform UI — desktop (JVM) and web (Wasm) targets
├── server/         Ktor HTTP server
├── docs/           Docusaurus user documentation site
└── gradle/         Version catalog and Gradle wrapper
```

### Building from Source

#### Prerequisites

| Requirement                    | Notes                                                                                                                                                                            |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **JDK 25+**                    | Required by the `shared` module.                                                                                                                                                 |
| **JetBrains Runtime (JBR) 25** | Required only for the **desktop** target. Install via IntelliJ IDEA Gradle JDK settings or download from [JBR releases](https://github.com/JetBrains/JetBrainsRuntime/releases). |
| **Gradle**                     | Wrapper included — no global install needed.                                                                                                                                     |

#### CLI

```shell
# Build the fat JAR
./gradlew :cli:shadowJar                                    # Linux / macOS
.\gradlew.bat :cli:shadowJar                                # Windows

# Run it
java -jar cli/build/libs/omnisign-<version>.jar --help

# Or run directly via Gradle
./gradlew :cli:run --args="--help"
```

Native installers (MSI, DEB, RPM, PKG, DMG) are built with `:cli:jpackage` — see the
[CLI README](cli/README.md) for the full command reference, installer packages, and usage examples.

#### Desktop

```shell
./gradlew :composeApp:run                                   # Linux / macOS
.\gradlew.bat :composeApp:run                               # Windows
```

See the [Compose UI README](composeApp/README.md) for native distribution packaging,
the web target, architecture details, and feature parity.

#### Server

```shell
./gradlew :server:run                                       # Linux / macOS
.\gradlew.bat :server:run                                   # Windows
```

See the [Server README](server/README.md) for configuration, authentication, and deployment.

#### Web (Wasm)

```shell
./gradlew :composeApp:wasmJsBrowserDevelopmentRun            # Linux / macOS
.\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun        # Windows
```

A local development server starts and opens the app in the default browser.

### Testing

The project uses **[Kotest 6](https://kotest.io/)** (FunSpec style),
**[MockK](https://mockk.io/)**, and
**[Arrow Kotest matchers](https://arrow-kt.io/learn/quickstart/#kotest)**.

```shell
./gradlew :shared:jvmTest          # Shared module (domain + DSS integration)
./gradlew :cli:test                # CLI command tests
./gradlew :server:test             # Server route tests
./gradlew :composeApp:jvmTest      # Desktop ViewModel tests
```

### Key Libraries

| Library                                                                                               | Purpose                                                        |
|-------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| [EU DSS 6.3](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html) | PAdES signing, validation, timestamping, trusted list handling |
| [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)                                | Shared business logic across JVM, Wasm, and future targets     |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)                           | Shared declarative UI for desktop and web                      |
| [Ktor](https://ktor.io/)                                                                              | HTTP server                                                    |
| [Clikt](https://ajalt.github.io/clikt/)                                                               | CLI argument parsing                                           |
| [Koin](https://insert-koin.io/)                                                                       | Dependency injection                                           |
| [Arrow](https://arrow-kt.io/)                                                                         | Functional error handling (`Either`-based `OperationResult`)   |
| [Kotest](https://kotest.io/) + [MockK](https://mockk.io/)                                             | Testing and mocking                                            |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)                              | JSON configuration persistence                                 |
| [Jackson](https://github.com/FasterXML/jackson)                                                       | YAML / XML config export & import                              |
| [Apache PDFBox](https://pdfbox.apache.org/)                                                           | PDF page rendering (desktop)                                   |
| [FileKit](https://github.com/nicholosP/filekit)                                                       | Cross-platform file picker dialogs                             |

## License

This project is licensed under the
[GNU Affero General Public License v3.0 or later](LICENSE.md) (AGPL-3.0-or-later).

Copyright © 2026 [Pizavo](mailto:pizavo@gmail.com).
