---
sidebar_position: 5
slug: config-overrides
---

# Config Overrides

The following options are available on `validate`, `sign`, and `timestamp` commands to override
configuration for a single execution without modifying any stored settings.
TSA credentials supplied here are held in memory only and are never written to disk.

## Available overrides

| Option                                  | Description                                                              |
|-----------------------------------------|--------------------------------------------------------------------------|
| `-H, --hash-algorithm <alg>`            | Hash algorithm override                                                  |
| `-E, --encryption-algorithm <alg>`      | Encryption (signing key) algorithm override                              |
| `-L, --signature-level <level>`         | Signature level override                                                 |
| `--timestamp-url <url>`                 | TSA URL override                                                         |
| `--timestamp-username <user>`           | TSA HTTP Basic username override                                         |
| `--timestamp-password <pass>`           | TSA HTTP Basic password override (in-memory only)                        |
| `--timestamp-timeout <ms>`              | TSA request timeout override in milliseconds                             |
| `--validation-policy <type>`            | Validation policy type override                                          |
| `--no-global-tls`                       | Exclude global trusted lists; use only profile-level TLs                 |
| `--disable-hash-algorithm <alg>`        | Disable a hash algorithm for this operation only. Repeatable.            |
| `--disable-encryption-algorithm <alg>`  | Disable an encryption algorithm for this operation only. Repeatable.     |

## Resolution order

Configuration values are resolved in this order (highest priority first):

1. **Operation overrides** — CLI flags on the current command
2. **Active profile** — settings from the profile activated with `config profile use` or `--profile`
3. **Global defaults** — values set with `config set`

For timestamp server settings, fields are merged at the field level: a per-execution
credential override does not discard the stored server URL, and vice versa.

## Environment variables

All options can also be set via environment variables using the `OMNISIGN_` prefix.
For example, `--timestamp-url` becomes `OMNISIGN_TIMESTAMP_URL`.
Explicit command-line options always take precedence over environment variables.

## Exit codes

| Code     | Meaning                                                                                 |
|----------|-----------------------------------------------------------------------------------------|
| `0`      | Success                                                                                 |
| `1`      | Command error (invalid arguments, missing required options, etc.)                       |
| non-zero | Operational failure (signing error, validation error, etc.) — details printed to stderr |

