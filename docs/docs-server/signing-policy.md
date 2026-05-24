---
sidebar_position: 3
---

# Signing policy (`signing.yml`)

`signing.yml` is the **provider-authored signing and validation policy** — the server's equivalent of
the desktop "global settings + profiles". It holds *how* the server signs and validates: default
algorithms, signature level, OCSP/CRL timeouts, the TSA, the validation policy, trusted certificates,
and named profiles. It is loaded **read-only** at startup; point [`server.yml`](configuration)'s
`signingConfigFile` at it.

A fully-commented template ships as **`signing.example.yml`** (in `server/src/main/resources/`).

- **YAML** is the primary format; a path ending in `.json` is parsed as JSON.
- **`${VAR}` substitution** — any value may reference an environment variable, expanded at load, so
  secrets live in the environment, not the file (the TSA password works this way). A referenced-but-
  unset variable fails startup.
- **Unknown keys fail startup.** The desktop-only `AppConfig` fields (`activeProfile`, `tlDrafts`,
  `renewalJobs`, `schedulerConfig`) are not part of this schema and are rejected if present.

## `global`

Defaults applied to every operation unless a profile or a strictly-tightening per-request override
narrows them.

```yaml
global:
  defaultHashAlgorithm: SHA256          # SHA256|SHA384|SHA512|SHA3_256|SHA3_384|SHA3_512|WHIRLPOOL|RIPEMD160
  # defaultEncryptionAlgorithm: RSA_SSA_PSS   # omit to infer from the certificate key
  defaultSignatureLevel: PADES_BASELINE_B     # B | T | LT | LTA
  disabledHashAlgorithms: [RIPEMD160, WHIRLPOOL]
  # disabledEncryptionAlgorithms: [DSA]
  ocsp: { timeout: 30000 }
  crl:  { timeout: 30000 }

  # RFC 3161 TSA — required for T / LT / LTA. Password from the environment only.
  # timestampServer:
  #   url: "http://tsa.example.org/tsr"
  #   username: "omnisign"
  #   password: "${OMNISIGN_TSA_PASSWORD}"
  #   timeout: 30000

  validation:
    policyType: DEFAULT_ETSI            # or CUSTOM_FILE (+ customPolicyPath)
    checkRevocation: true
    useEuLotl: true
    # customTrustedLists:               # extra ETSI TS 119612 lists, by reference
    #   - name: "national-tl"
    #     source: "https://tl.example.gov/tl.xml"     # https:// or file://
    #     signingCertPath: "/etc/omnisign/tl-signing.crt"
    # trustedCertificates:              # directly-trusted anchors, by reference
    #   - path: certs/internal-root.pem # exactly one of path | inline
    #     type: ANY                     # ANY | CA | TSA
    #     fingerprint: "sha256-<hex>"   # optional integrity pin (fails startup on mismatch)
    # algorithmConstraints:
    #   expirationLevel: FAIL           # FAIL | WARN | INFORM | IGNORE
    #   expirationLevelAfterUpdate: WARN
    #   policyUpdateDate: "2024-10-13"
    #   expirationDateOverrides: { SHA512: "2035-01-01" }

  # customPkcs11Libraries:              # only when the server signs with a PKCS#11 token
  #   - { name: "vendor-token", path: "/usr/lib/vendor-pkcs11.so" }
  pkcs11ProbeTimeoutSeconds: 30
  trustedListRefreshIntervalHours: 24
```

### Trusted certificates

`trustedCertificates` are declared **by reference** and reconciled into the server's writable
[`trustStoreDir`](configuration#truststoredir) at boot. Each entry sets exactly one of `path` (a
PEM/DER file, relative to `signing.yml`) or `inline` (Base64 DER), plus a required `type`
(`ANY`/`CA`/`TSA`) and an optional `fingerprint` integrity pin. The bytes are copied into the trust
directory, so the source file may be removed afterward. A fatal reconcile condition (integrity
mismatch, unresolvable reference) aborts startup.

## `profiles`

Named overrides selected per request via the `profile` field (e.g. `?profile=archival`). A profile's
identity is its inner `name`; a name appearing in more than one source fails startup. Every
per-profile field is optional and overrides the matching `global` value.

Profiles come from any combination of three sources:

```yaml
profiles:
  inline:                              # 1. defined directly
    - name: archival
      description: "Long-term archival (B-LTA)"
      hashAlgorithm: SHA512
      signatureLevel: PADES_BASELINE_LTA
  # files:                             # 2. one bare profile per file
  #   - profiles/archival-lta.yml
  # directories:                       # 3. scanned recursively for *.yml / *.yaml
  #   - profiles/
```

See [`signing.example.yml`](https://github.com/pizavo/omnisign/blob/main/server/src/main/resources/signing.example.yml)
for the fully-commented template, and the [API reference](api-reference) for how clients select a
profile per request.
