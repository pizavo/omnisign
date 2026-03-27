# OmniSign

Multiplatform digital signature verification, signing, and re-timestamping application built on the
[EU Digital Signature Service (DSS)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html)
library. Supports **PAdES BASELINE B / B-T / B-LT / B-LTA** for PDF documents (including PDF/A-3b).

## Features

- **Signing** — Sign PDF documents using X.509 certificates stored in PKCS#12 files, PKCS#11 hardware tokens
  (including qualified ones), the Windows Certificate Store, or the macOS Keychain.
- **Validation** — Validate electronically signed PDFs against eIDAS (EU LOTL), custom trusted lists,
  or standalone PKIX chains. CRL and OCSP revocation checking are included.
- **Timestamping** — Extend signatures from B-B → B-T → B-LT → B-LTA using RFC 3161 timestamp servers.
- **Archival (Digital Continuity)** — Automatic re-timestamping of B-LTA documents before archival
  timestamps expire, managed by an OS-level scheduler.
- **Custom Trusted Lists** — Build and register your own ETSI Trusted Lists for non-eIDAS environments.
- **Configurable Algorithms** — SHA-256, SHA-384, SHA-512 hash algorithms out of the box, with support
  for Whirlpool, RIPEMD-160, and future post-quantum algorithms. Per-algorithm expiration date management
  aligned with ETSI TS 119 312.
- **Profiles** — Named configuration profiles for different signing/validation contexts
  (e.g., university, personal, corporate).
- **JSON Output** — Machine-readable JSON mode for scripting and integration.

## Platforms

| Platform                        | Module        | Technology               |
|---------------------------------|---------------|--------------------------|
| CLI (Linux, Windows, macOS)     | `cli`         | Kotlin/JVM               |
| Desktop (Linux, Windows, macOS) | `composeApp`  | Compose Multiplatform    |
| Server                          | `server`      | Ktor (Kotlin/JVM)        |
| Web                             | `composeApp`  | Compose for Web (Wasm)   |

## Project Structure

```
omnisign/
├── shared/         Multiplatform core — domain models, use cases, DSS integration
├── cli/            Command-line interface (fat JAR + native installers)
├── composeApp/     Compose Multiplatform UI — desktop (JVM) and web (Wasm) targets
├── server/         Ktor HTTP server
└── gradle/         Version catalog and Gradle wrapper
```

## Quick Start

### Prerequisites

- **JDK 25+** (required by the shared module)
- **Gradle** (wrapper included)

### Build & Run — CLI

```shell
# Fat JAR (recommended)
.\gradlew.bat :cli:shadowJar
java -jar cli/build/libs/omnisign-<version>.jar --help

# Or run directly via Gradle
.\gradlew.bat :cli:run --args="--help"
```

See the [CLI README](cli/README.md) for the full command reference, installer packages,
and usage examples.

### Build & Run — Desktop

```shell
# Linux / macOS
./gradlew :composeApp:run

# Windows
.\gradlew.bat :composeApp:run
```

See the [Compose UI README](composeApp/README.md) for native distribution packaging,
the web (Wasm) target, architecture details, and platform feature parity.

### Build & Run — Server

```shell
# Linux / macOS
./gradlew :server:run

# Windows
.\gradlew.bat :server:run
```

### Build & Run — Web (Wasm)

```shell
# Linux / macOS
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Windows
.\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun
```

## Key Libraries

| Library                                                                                               | Purpose                                                        |
|-------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| [EU DSS 6.3](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html) | PAdES signing, validation, timestamping, trusted list handling |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)                           | Shared UI for desktop and web                                  |
| [Ktor](https://ktor.io/)                                                                              | HTTP server                                                    |
| [Clikt](https://ajalt.github.io/clikt/)                                                               | CLI argument parsing                                           |
| [Koin](https://insert-koin.io/)                                                                       | Dependency injection                                           |
| [Arrow](https://arrow-kt.io/)                                                                         | Functional error handling                                      |
| [Kotest](https://kotest.io/)                                                                          | Testing framework                                              |

## License

This project is licensed under the [GNU Affero General Public License v3.0 or later](LICENSE.md) (AGPL-3.0-or-later).
