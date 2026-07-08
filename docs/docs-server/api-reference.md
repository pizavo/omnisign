---
sidebar_position: 7
---

# API reference

All operational endpoints live under **`/api/v1`**; authentication endpoints live under **`/auth`**.
When [authentication](authentication) is enabled, the operational and config routes require a
`Authorization: Bearer <jwt>` header; `/api/v1/health`, `/api/v1/capabilities`, and the `/auth/*`
routes are always reachable without one.

File uploads are `multipart/form-data`. The `profile` field (or `?profile=` query parameter) selects
a named [signing profile](signing-policy#profiles); when omitted, `global` defaults apply — there is
no server-side "active profile" fallback. `disableHashAlgorithm` and `disableEncryptionAlgorithm` are
comma-separated, case-insensitive, and **strictly tightening** (they can only add to the disabled
set, never re-enable something a profile or the global policy disabled).

Errors are a JSON `ApiError`: `{ "error": "CODE", "message": "human-readable detail" }`.

## Operations

### `POST /api/v1/sign`

Sign a PDF. Requires the **`SIGN`** operation to be enabled.

| Field              | Required | Notes                                                        |
|--------------------|:--------:|-------------------------------------------------------------|
| `file`             |    ✅    | The PDF to sign.                                             |
| `certificateAlias` |          | Signing certificate alias; must pass `certificateAliases` allowlist if set. |
| `hashAlgorithm`    |          | e.g. `SHA256`.                                               |
| `signatureLevel`   |          | e.g. `PADES_BASELINE_T`.                                     |
| `reason`, `location`, `contactInfo` | | Signature metadata.                            |
| `noTimestamp`      |          | `true` to omit the RFC 3161 timestamp.                       |
| `profile`          |          | Named profile.                                              |
| `disableHashAlgorithm` / `disableEncryptionAlgorithm` | | Tightening overrides.                  |

**Response** `200`: the signed PDF (`application/pdf`). The `X-OmniSign-Result` header carries a JSON
`SigningResultMeta` (`signatureId`, `signatureLevel`, `annotatedWarnings`, `hasRevocationWarnings`).

**Errors**: `400 MALFORMED_DOCUMENT` when the upload is not a PDF; `422 ENCRYPTED_DOCUMENT` when the
PDF is encrypted or password-protected and cannot be signed.

### `POST /api/v1/validate`

Validate a PDF's signatures. Requires **`VALIDATE`** (enabled by default).

| Field                | Required | Notes                                                       |
|----------------------|:--------:|------------------------------------------------------------|
| `file`               |    ✅    | The PDF to validate.                                        |
| `profile`            |          | Named profile.                                             |
| `formats`            |          | Comma-separated `RawReportFormat` names to include raw XML reports; unknown names → `400 INVALID_FORMAT`. |
| `disableHashAlgorithm` / `disableEncryptionAlgorithm` | | Tightening overrides.                  |

**Response** `200`: a JSON validation report. Each signature and document timestamp carries its
full certificate chain — every certificate's parsed fields and extensions, plus which certificates
are trusted and via which source (a trusted list, the global trust, or a profile's).

The report is localized per request from the `Accept-Language` header (e.g. `cs`, `sk`), falling back
to the server's default locale when the header is absent or names an unshipped language — so each
caller can receive the report in its own language on a shared, multi-user server.

### `POST /api/v1/timestamp`

Extend a signed PDF to a higher PAdES level (adds a document timestamp). Requires **`TIMESTAMP`**.

| Field          | Required | Notes                                                            |
|----------------|:--------:|----------------------------------------------------------------|
| `file`         |    ✅    | The signed PDF to extend.                                       |
| `targetLevel`  |          | Target PAdES level; defaults to `PADES_BASELINE_LTA`.          |
| `profile`      |          | Named profile.                                                 |
| `disableHashAlgorithm` / `disableEncryptionAlgorithm` | | Tightening overrides.                      |

The TSA is always the server's configured one — clients cannot supply TSA credentials.

**Response** `200`: the extended PDF (`application/pdf`). The `X-OmniSign-Result` header carries a JSON
`TimestampResultMeta` (`newLevel`, `annotatedWarnings`).

**Errors**: `400 MALFORMED_DOCUMENT` when the upload is not a PDF; `422 ENCRYPTED_DOCUMENT` when the
PDF is encrypted or password-protected and cannot be extended.

### `POST /api/v1/timestamp/inspect`

Pre-flight a signed PDF to learn which target levels are valid extensions (without full validation).
Requires **`TIMESTAMP`**. Field: `file` (required). **Response** `200`: a JSON
`DocumentTimestampInfo` (`hasDocumentTimestamp`, `containsLtData`).

### `GET /api/v1/certificates`

List the server's signing-capable certificates (filtered by `certificateAliases` when set), plus
per-token warnings and locked-token entries. When the server is configured with a
[file signing keystore](configuration#operations) (`operations.signingKeystorePath`), that keystore's
certificate(s) are enumerated here too, so a remote client can select the server's signing identity.
Requires **`SIGN`** because it reveals installed signing material. **Response** `200`: JSON.

## Configuration introspection

Read-only, sanitized (credentials stripped); grouped with the operational routes, so a JWT is
required when auth is enabled.

| Endpoint                              | Response                                              |
|---------------------------------------|------------------------------------------------------|
| `GET /api/v1/config/global`           | Global defaults.                                     |
| `GET /api/v1/config/profiles`         | All profiles (sorted by name).                       |
| `GET /api/v1/config/profiles/{name}`  | One profile, or `404 PROFILE_NOT_FOUND`.             |
| `GET /api/v1/config/resolved?profile={name}` | Effective config after merge (omit `profile` for global), or `404`/`422`. |
| `GET /api/v1/config/trusted-certificates?profile={name}` | Trusted certificates for the global scope, or a profile's when `profile` is given. JSON array of `{ fingerprint, subjectDN, notBefore, notAfter, type }` (`type` ∈ `ANY`/`CA`/`TSA`). |
| `GET /api/v1/config/export`           | The full configuration as a ZIP (`application/zip` attachment, `omnisign-config.zip`) — global settings, every profile, and their trusted certificates, identical to the desktop Backup export. Contains no secrets. |

## System

Always public.

| Endpoint                     | Response                                                          |
|------------------------------|------------------------------------------------------------------|
| `GET /api/v1/health`         | `HealthResponse` (`status`, `version`, `poweredBy`, plus `organizationName` when [branding](configuration#organizationname) is set) — for monitoring probes. |
| `GET /api/v1/capabilities`   | `CapabilitiesResponse` (`allowedOperations`, `profiles`, `maxFileSize`, `authEnabled`, `poweredBy`, plus `organizationName` when [branding](configuration#organizationname) is set). With auth on, `profiles` is empty for unauthenticated callers. |

## Authentication {#auth}

See [Authentication & SSO](authentication) for the full flow.

| Endpoint                       | Auth | Notes                                                       |
|--------------------------------|:----:|------------------------------------------------------------|
| `GET /auth/login`              |  —   | Lists providers; each has a `loginUrl` (`/auth/redirect/{name}` for OIDC, `/auth/callback/{name}` for header-injection). |
| `GET /auth/redirect/{name}`    |  —   | Starts the OIDC authorization-code + PKCE flow.            |
| `GET /auth/callback/{name}`    |  —   | OAuth2 / header-injection callback; mints the token pair.  |
| `GET /auth/session`            | JWT  | Returns `SessionResponse` for the bearer, else `401`.      |
| `POST /auth/refresh`           |  —   | Body `{ "refreshToken": "…" }`; single-use rotation. `401 INVALID_REFRESH_TOKEN` / `401 SESSION_EXPIRED`. |
| `POST /auth/logout`            |  —   | Body `{ "refreshToken": "…" }`; idempotent, always `204`.  |

The callbacks and `/auth/refresh` return a `TokenResponse`:

```json
{
  "token": "<access JWT>",
  "refreshToken": "<opaque, single-use>",
  "expiresIn": 3600,
  "user": { "userId": "…", "email": "…", "displayName": "…", "providerName": "google" }
}
```
