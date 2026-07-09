---
sidebar_position: 1
---

# Overview

OmniSign uses a layered configuration system with three levels of precedence:

1. **Global defaults** — stored in the configuration file
2. **Named profiles** — override specific global settings when activated
3. **Operation overrides** — CLI flags that override everything for a single execution

Configuration is stored as JSON at a platform-specific location:

| OS      | Path                                                 |
|---------|------------------------------------------------------|
| Linux   | `~/.config/omnisign/config.json`                     |
| macOS   | `~/Library/Application Support/omnisign/config.json` |
| Windows | `%APPDATA%\omnisign\config.json`                     |

TSA passwords are **never** stored in the configuration file — they are persisted exclusively
in the OS-native credential store (Windows Credential Manager, macOS Keychain,
or Secret Service / KWallet on Linux).

## `config show`

Print the current configuration including global defaults, active profile, and all named profiles.

```bash
omnisign config show
```

## `config path`

Print the resolved configuration file path. Useful for scripting or debugging.

```bash
omnisign config path
```

## `config set`

Modify the global (default) configuration. Only provided options are updated.

```
omnisign config set [options]
```

| Option                                         | Description                                                     |
|------------------------------------------------|-----------------------------------------------------------------|
| `-H, --hash-algorithm <alg>`                   | Default hash algorithm (`SHA256`, `SHA384`, `SHA512`, …)        |
| `-E, --encryption-algorithm <alg>`             | Default encryption (signing key) algorithm                      |
| `-L, --signature-level <level>`                | Default signature level (`PADES_BASELINE_B/T/LT/LTA`)           |
| `--timestamp-url <url>`                        | Default timestamp server (TSA) URL                              |
| `--timestamp-username <user>`                  | Default TSA HTTP Basic username                                 |
| `--timestamp-password <pass>`                  | Default TSA password (stored in OS keychain; use `-` to prompt) |
| `--timestamp-timeout <ms>`                     | Default TSA request timeout in milliseconds                     |
| `--validation-policy <type>`                   | Default validation policy (`DEFAULT_ETSI` or `CUSTOM_FILE`)     |
| `--check-revocation <bool>`                    | Enable/disable certificate revocation checking                  |
| `--use-eu-lotl <bool>`                         | Enable/disable the EU List of Trusted Lists                     |
| `--algo-expiration-level <level>`              | Severity when an algorithm's expiration date has passed         |
| `--algo-expiration-level-after-update <level>` | Severity after the policy update date                           |
| `--algo-expiry-override <ALG=DATE>`            | Per-algorithm expiration date override (repeatable)             |
| `--disable-hash-algorithm <alg>`               | Globally disable a hash algorithm. Repeatable.                  |
| `--enable-hash-algorithm <alg>`                | Re-enable a globally disabled hash algorithm. Repeatable.       |
| `--disable-encryption-algorithm <alg>`         | Globally disable an encryption algorithm. Repeatable.           |
| `--enable-encryption-algorithm <alg>`          | Re-enable a globally disabled encryption algorithm. Repeatable. |
| `--pkcs11-probe-timeout <1-120>`               | Max seconds to wait for a single PKCS#11 library probe          |
| `--trusted-list-refresh-interval <hours>`      | Hours a downloaded trusted list is kept before background refresh (min 1) |

### Example

```bash
omnisign config set \
  --hash-algorithm SHA512 \
  --signature-level PADES_BASELINE_LTA \
  --timestamp-url https://tsa.example.com/tsr \
  --use-eu-lotl true
```

## `config export`

Export the global configuration (or the full application configuration), together with the trusted
certificates it references, to a **ZIP archive**.

```
omnisign config export <output-file> [options]
```

| Option               | Description                                                                                                                   |
|----------------------|------------------------------------------------------------------------------------------------------------------------------|
| `<output-file>`      | **(Required)** Destination archive path (a ZIP, conventionally `.zip`)                                                        |
| `-f, --format <fmt>` | Format of the configuration *inside* the archive (`JSON`, `XML`, `YAML`). Inferred from the output extension, otherwise JSON. |
| `-a, --all`          | Export the full application configuration instead of only the global section.                                                 |

The archive always bundles the configuration plus the trusted-certificate material for the exported
scope, so a restore is self-contained.

## `config import`

Import from a ZIP archive produced by `config export` — its bundled trusted certificates are restored
into the trust store. A legacy plain configuration file (the older text format) is also accepted and
auto-detected.

```
omnisign config import <input-file> [options]
```

| Option               | Description                                                                                                   |
|----------------------|--------------------------------------------------------------------------------------------------------------|
| `<input-file>`       | **(Required)** Source path: a ZIP archive, or a legacy plain configuration file                               |
| `-f, --format <fmt>` | Format of a legacy plain file (`JSON`, `XML`, `YAML`); ignored for ZIP archives, inferred from the extension. |
| `-a, --all`          | Import as a full application configuration, replacing all sections.                                           |

## Related pages

| Topic                                | Description                                               |
|--------------------------------------|-----------------------------------------------------------|
| [Profiles](profiles)                 | Named configuration presets (`config profile`)            |
| [Trusted Lists](trusted-lists)       | Custom ETSI TL sources and the TL builder (`config tl`)   |
| [Trusted Certificates](trust)        | Directly trusted CA/TSA certificates (`config trust`)     |
| [PKCS#11 Libraries](pkcs11)          | Custom PKCS#11 middleware registrations (`config pkcs11`) |
| [Config Overrides](config-overrides) | Per-execution overrides and environment variables         |

