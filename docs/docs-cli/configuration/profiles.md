---
sidebar_position: 2
---

# Profiles

Profiles let you store sets of configuration values that can be activated per-operation
with `--profile <name>`. This is useful when you work with multiple timestamp servers,
different signature levels, or organisation-specific validation policies.

## Profile management

| Subcommand                               | Description                                                     |
|------------------------------------------|-----------------------------------------------------------------|
| `config profile list`                    | List all profiles                                               |
| `config profile show [name]`             | Show all settings of a profile (defaults to the active profile) |
| `config profile create <name> [options]` | Create or replace a profile                                     |
| `config profile edit <name> [options]`   | Update specific fields of an existing profile                   |
| `config profile use <name>`              | Set a profile as the default active profile                     |
| `config profile remove <name>`           | Delete a profile                                                |
| `config profile export <name> <file>`    | Export a profile to a file                                      |
| `config profile import <file> [options]` | Import a profile from a file                                    |

## Create and edit options

`create` and `edit` accept the following options:

| Option                                         | Description                                                  |
|------------------------------------------------|--------------------------------------------------------------|
| `--description <text>`                         | Profile description                                          |
| `-H, --hash-algorithm <alg>`                   | Hash algorithm override                                      |
| `-E, --encryption-algorithm <alg>`             | Encryption algorithm override                                |
| `-L, --signature-level <level>`                | Signature level override                                     |
| `--timestamp-url <url>`                        | Timestamp server URL                                         |
| `--timestamp-username <user>`                  | TSA HTTP Basic username                                      |
| `--timestamp-password <pass>`                  | TSA password (stored in OS keychain; use `-` to prompt)      |
| `--timestamp-timeout <ms>`                     | TSA request timeout in milliseconds                          |
| `--validation-policy <type>`                   | Validation policy type                                       |
| `--algo-expiration-level <level>`              | Algorithm expiration severity                                |
| `--algo-expiration-level-after-update <level>` | Algorithm expiration severity after policy update date       |
| `--algo-expiry-override <ALG=DATE>`            | Per-algorithm expiration override (repeatable)               |
| `--disable-hash-algorithm <alg>`               | Disable a hash algorithm in this profile (repeatable)        |
| `--disable-encryption-algorithm <alg>`         | Disable an encryption algorithm in this profile (repeatable) |

`edit` additionally supports `--enable-hash-algorithm`, `--enable-encryption-algorithm`,
and the following `--clear-*` flags to unset individual fields:

| Clear flag                      | Effect                                                      |
|---------------------------------|-------------------------------------------------------------|
| `--clear-description`           | Remove the profile description                              |
| `--clear-hash-algorithm`        | Fall back to the global hash algorithm                      |
| `--clear-encryption-algorithm`  | Fall back to the global encryption algorithm                |
| `--clear-signature-level`       | Fall back to the global signature level                     |
| `--clear-timestamp`             | Remove the entire timestamp server config from this profile |
| `--clear-validation-policy`     | Fall back to the global validation policy                   |
| `--clear-algo-constraints`      | Reset all algorithm constraint overrides to defaults        |
| `--clear-algo-expiry-overrides` | Remove all per-algorithm expiration date overrides          |

## Export and import

**Export:**

```bash
omnisign config profile export university university.json
omnisign config profile export university university.yaml --format YAML
```

| Option               | Description                                                                       |
|----------------------|-----------------------------------------------------------------------------------|
| `-f, --format <fmt>` | Export format (`JSON`, `XML`, `YAML`). Inferred from file extension when omitted. |

**Import:**

```bash
omnisign config profile import university.json
omnisign config profile import university.json --name university-backup
```

| Option              | Description                                                                               |
|---------------------|-------------------------------------------------------------------------------------------|
| `-f, --format`      | Import format (`JSON`, `XML`, `YAML`). Inferred from file extension when omitted.         |
| `-n, --name <name>` | Override the profile name from the file.                                                  |

## Examples

```bash
# Create a profile for university document signing
omnisign config profile create university \
  --description "University thesis signing" \
  --signature-level PADES_BASELINE_LTA \
  --hash-algorithm SHA512 \
  --timestamp-url https://tsa.university.example.com/tsr

# Make it the default active profile
omnisign config profile use university

# Sign using the profile explicitly
omnisign sign -f thesis.pdf -o thesis-signed.pdf --profile university

# Update the TSA URL in an existing profile
omnisign config profile edit university --timestamp-url https://new-tsa.example.com/tsr

# Remove the timestamp config from a profile (fall back to global)
omnisign config profile edit university --clear-timestamp
```

