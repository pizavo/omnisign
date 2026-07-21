# OmniSign Server

HTTP backend for **OmniSign** — a multiplatform digital signature tool built on the
[EU Digital Signature Service (DSS)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html).
Built with [Ktor](https://ktor.io/) (Netty), it exposes the same PAdES BASELINE B / B-T / B-LT / B-LTA
signing, validation, and re-timestamping operations as the desktop and CLI apps over a REST API —
for organisation-wide deployment behind your own authentication and policy.

This is a **provider/operator-facing** component: you deploy it, an administrator configures *how* it
signs and validates and *who* may reach it, and clients (the web frontend, scripts, or other services)
call its API. Full guides live on the [documentation site](https://pizavo.github.io/omnisign/server/).

## Building & Running

```shell
# Run directly
./gradlew :server:run                                       # Linux / macOS
.\gradlew.bat :server:run                                   # Windows

# Build a self-contained fat JAR → server/build/libs/server-all.jar
./gradlew :server:buildFatJar                               # Linux / macOS
.\gradlew.bat :server:buildFatJar                           # Windows

# Or a launch-script distribution → server/build/install/server/bin/
./gradlew :server:installDist                               # Linux / macOS
.\gradlew.bat :server:installDist                           # Windows
```

Requires **JDK 25+**. With the defaults the server binds to **`127.0.0.1:18080`** (loopback) and
allows only the **`VALIDATE`** operation.

## Configuration

The server reads **two** files, deliberately separated:

| File          | Purpose                                                                                          |
|---------------|--------------------------------------------------------------------------------------------------|
| `server.yml`  | *Exposure and security* — network bind, TLS, CORS, reverse proxy, rate limiting, auth, allowed operations. |
| `signing.yml` | *Signing/validation policy* — default algorithms, signature level, TSA, validation policy, trusted certificates, and named profiles (the server's "global settings + profiles"). Referenced from `server.yml` via `signingConfigFile`. |

Commented templates ship as
[`server.example.yml`](src/main/resources/server.example.yml) and
[`signing.example.yml`](src/main/resources/signing.example.yml). `server.yml` is resolved from
`--config <path>`, then `OMNISIGN_SERVER_CONFIG`, then `server.yml` in the working directory.
Both files are parsed strictly — **unknown keys fail startup** — and support `${VAR}` substitution.

**Secrets are supplied through the environment only**, never in YAML: `OMNISIGN_JWT_SECRET`,
`OMNISIGN_OIDC_<NAME>_CLIENT_SECRET`, `OMNISIGN_TLS_KEYSTORE_PASSWORD` /
`OMNISIGN_TLS_PRIVATE_KEY_PASSWORD`, the TSA password, and `OMNISIGN_EXTERNAL_URL`.

→ [Configuration reference](https://pizavo.github.io/omnisign/server/configuration) ·
[Signing policy](https://pizavo.github.io/omnisign/server/signing-policy)

## API

All operations live under `/api/v1`; authentication endpoints under `/auth`. Operations are gated by
`operations.allowed`, and when `auth.enabled` is `true` every operational route requires a JWT Bearer
token (`/health`, `/capabilities`, and `/auth/*` stay public).

| Endpoint                          | Gate        | Description                                              |
|-----------------------------------|-------------|---------------------------------------------------------|
| `POST /api/v1/sign`               | `SIGN`      | Sign a PDF (multipart) → signed PDF + `X-OmniSign-Result` |
| `POST /api/v1/validate`           | `VALIDATE`  | Validate a PDF (multipart) → JSON report                 |
| `POST /api/v1/timestamp`          | `TIMESTAMP` | Extend a signed PDF to a higher PAdES level             |
| `POST /api/v1/timestamp/inspect`  | `TIMESTAMP` | Pre-flight which target levels are valid extensions     |
| `GET /api/v1/certificates`        | `SIGN`      | List the server's signing certificates                  |
| `GET /api/v1/config/*`            | auth        | Read-only, sanitized config (global / profiles / resolved / trusted-certificates) plus `config/export` (whole config as a ZIP) |
| `GET /api/v1/health`              | public      | Health probe                                            |
| `GET /api/v1/capabilities`        | public      | Allowed operations, profiles, upload limits             |
| `/auth/login` · `/auth/redirect/{p}` · `/auth/callback/{p}` · `/auth/exchange` · `/auth/session` · `/auth/refresh` · `/auth/logout` | mixed | SSO login flow and session management |

→ [API reference](https://pizavo.github.io/omnisign/server/api/omnisign-server-api) (interactive — request/response schemas and a "Try it" console)

## Authentication

SSO is optional (`auth.enabled`) and uses **OIDC** providers — presets for Google, Microsoft
(Entra ID), Amazon Cognito, Keycloak, GitHub, GitLab, Auth0, Apple, and **eduID.cz** (the Czech
academic federation). Authorization-code flow with PKCE, id_token verification, and single-use
refresh-token rotation. A required `allowedEmailDomains` (and optional `requiredClaims`) restricts
who may sign in.

Sessions are JWTs (HS256/384/512; RS\*/ES\* planned).

→ [Authentication & SSO](https://pizavo.github.io/omnisign/server/authentication)

## Security

The defaults are closed: loopback bind, `VALIDATE`-only. Binding to a non-loopback host **requires**
TLS or a trusted reverse proxy, or startup is refused. `SIGN` and `TIMESTAMP` are opt-in;
`cors.allowedOrigins` is required; per-IP rate limiting and reverse-proxy trusted-IP handling are
available.

→ [Security](https://pizavo.github.io/omnisign/server/security)

## Deployment

A [`Dockerfile`](Dockerfile) (Temurin 25 JRE) is included — it expects the fat JAR to be built first,
declares `/app/config`, `/app/trusted-certs`, and `/app/data` volumes, and exposes ports
**18080**/**18443**. Bare-metal deployment uses `installDist` + systemd; the recommended production
topology terminates TLS at a reverse proxy with `proxy.enabled: true`.

→ [Deployment](https://pizavo.github.io/omnisign/server/deployment)

## Testing

```shell
./gradlew :server:test                                      # Linux / macOS
.\gradlew.bat :server:test                                  # Windows
```

Tests use **Kotest 6** (FunSpec style), **MockK**, and **Arrow Kotest matchers**.

## Key Libraries

| Library                  | Purpose                                                       |
|--------------------------|---------------------------------------------------------------|
| Ktor (Netty)             | HTTP server — routing, Auth + JWT, CORS, rate limiting        |
| Koin                     | Dependency injection (`koin-ktor`)                            |
| kotlinx.serialization    | JSON request/response payloads                               |
| Jackson                  | YAML parsing for `server.yml` / `signing.yml`                |
| `shared` module          | EU DSS signing, validation, and timestamping core            |

## Documentation

Full guides — configuration, signing policy, authentication, security, deployment, and the API
reference — are on the [documentation site](https://pizavo.github.io/omnisign/server/).
