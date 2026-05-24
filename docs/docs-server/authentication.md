---
sidebar_position: 4
---

# Authentication & SSO

When `auth.enabled` is `true`, every operational API route requires a valid **JWT Bearer token**;
`/api/v1/health`, `/api/v1/capabilities`, and the `/auth/*` routes stay public. Users obtain a token
by signing in through a configured SSO provider. Without an `auth:` block the server installs no
authentication (suitable only for a trusted private network or a `VALIDATE`-only deployment).

```yaml
auth:
  enabled: true
  session:
    algorithm: HS512          # HS256 | HS384 | HS512 (HMAC). RS*/ES* are planned, not yet implemented.
    issuer: omnisign
    audience: omnisign-api
    tokenExpirySeconds: 3600
  providers:
    - type: oidc
      name: google
      preset: GOOGLE
      displayName: "Sign in with Google"
      clientId: "YOUR_CLIENT_ID.apps.googleusercontent.com"
      allowedEmailDomains: ["yourcompany.com"]
```

The JWT signing secret comes from **`OMNISIGN_JWT_SECRET`** (required when auth is enabled) — never
from YAML. See [Configuration → Secrets](configuration#secrets).

## OIDC providers

Each OIDC provider needs a `preset`, a `clientId`, and a **required** `allowedEmailDomains`, plus a
client secret in `OMNISIGN_OIDC_<NAME>_CLIENT_SECRET` (where `<NAME>` is the provider `name`,
uppercased, non-alphanumerics → `_`; e.g. `name: google` → `OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET`).

| Preset          | `tenantId`                        | Notes                                              |
|-----------------|-----------------------------------|----------------------------------------------------|
| `GOOGLE`        | —                                 |                                                    |
| `MICROSOFT`     | `common` / `organizations` / GUID | Entra ID (Azure AD)                                |
| `AMAZON_COGNITO`| `{region}/{userPoolId}`           |                                                    |
| `KEYCLOAK`      | `{host}/{realm}`                  | self-hosted                                        |
| `GITHUB`        | —                                 | OAuth2 (not full OIDC); needs `scopes: [read:user, user:email]` |
| `GITLAB`        | —                                 |                                                    |
| `AUTH0`         | your Auth0 domain                 |                                                    |
| `APPLE`         | —                                 | public IdP — domain-restrict                       |
| `EDUID_CZ`      | —                                 | Czech academic federation (see below)             |

`allowedEmailDomains` is mandatory — pick `["*"]` (deliberate allow-all, e.g. a private Cognito pool
or self-hosted Keycloak realm), `["yourcompany.com"]`, or several. An empty list or a missing field
is rejected at startup. `requiredClaims` (a map of claim → allowed values, at least one must match)
adds finer control.

### eduID.cz (Czech academic federation)

`EDUID_CZ` is a **federation**: one `clientId`/secret grants access to users from all ~60+ federated
Czech universities — the `clientId` does **not** restrict which institution can log in. Restrict by
institution with `allowedEmailDomains` (e.g. `["osu.cz"]`), or more authoritatively with
`requiredClaims.schac_home_organization`, or by institution **and** role with
`requiredClaims.eduperson_scoped_affiliation` (e.g. `["staff@osu.cz", "faculty@osu.cz"]`).

```yaml
    - type: oidc
      name: eduid
      preset: EDUID_CZ
      displayName: "Sign in with eduID.cz"
      clientId: "YOUR_EDUID_CLIENT_ID"
      scopes: ["openid", "email", "profile", "eduperson_principal_name"]
      allowedEmailDomains: ["osu.cz"]
```

## Shibboleth / header injection

For institutions running a Shibboleth SP (Apache + `mod_shib`) as a reverse proxy: the SP
authenticates the user and injects identity attributes as headers. The OmniSign port must **not** be
reachable directly — only the proxy should be exposed (see [Security → Reverse proxy](security#reverse-proxy)).

```yaml
    - type: header-injection
      name: shibboleth
      displayName: "University SSO (Shibboleth)"
      userHeader: "X-Remote-User"
      emailHeader: "X-Shib-Mail"
      displayNameHeader: "X-Shib-Cn"
```

The proxy must also send a configured shared-secret header so the server only trusts callbacks coming
through it; the secret is supplied from the environment.

## Login flow and tokens

1. `GET /auth/login` returns the active providers and each one's `loginUrl`.
2. The client sends the user to that URL; OmniSign runs the OAuth2 **authorization-code flow with
   PKCE** (RFC 7636) for OIDC providers.
3. On the callback, OmniSign verifies the **id_token** signature and its `iss`/`aud`/`exp` and
   cross-checks `sub` against UserInfo, then enforces `allowedEmailDomains` and `requiredClaims`.
4. It issues a short-lived **access JWT** plus a single-use **refresh token** (rotated on every
   `POST /auth/refresh`, bounded by the session's maximum lifetime).

Set **`OMNISIGN_EXTERNAL_URL`** to your public base URL so the OAuth2 redirect URIs are built
correctly behind a proxy. See the [API reference](api-reference#auth) for the `/auth` endpoints and
[`server.example.yml`](https://github.com/pizavo/omnisign/blob/main/server/src/main/resources/server.example.yml)
for every provider's full example.
