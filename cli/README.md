# omnisign CLI

Command-line interface for **omnisign** — a multiplatform digital signature tool built on the
[EU Digital Signature Service (DSS)](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html).
Supports PAdES BASELINE B / B-T / B-LT / B-LTA signing, validation, timestamping, and certificate discovery.

## Building

### Fat JAR (recommended for distribution)

```shell
# Linux / macOS
./gradlew :cli:shadowJar

# Windows
.\gradlew.bat :cli:shadowJar
```

The self-contained JAR is written to `cli/build/libs/omnisign-cli-<version>.jar`.

### Run directly via Gradle

```shell
# Linux / macOS
./gradlew :cli:run --args="<command> [options]"

# Windows
.\gradlew.bat :cli:run --args="<command> [options]"
```

### Native distribution (zip / tar)

```shell
# Linux / macOS
./gradlew :cli:installDist

# Windows
.\gradlew.bat :cli:installDist
```

Launch scripts are generated in `cli/build/install/cli/bin/`.

## Running

```shell
java -jar omnisign-cli-<version>.jar <command> [options]
```

Or, when using the install distribution:

```shell
# Linux / macOS
./cli/build/install/cli/bin/cli <command> [options]

# Windows
.\cli\build\install\cli\bin\cli.bat <command> [options]
```

## Command Reference

### Top-level

```
omnisign [command]
```

| Command        | Description                                              |
|----------------|----------------------------------------------------------|
| `validate`     | Validate a signed PDF document                          |
| `sign`         | Sign a PDF document with a PAdES digital signature      |
| `timestamp`    | Extend a signed PDF to a higher PAdES level             |
| `certificates` | Discover and inspect available certificates             |
| `config`       | Manage application configuration                        |

---

### `validate` — Validate a signed PDF

```
omnisign validate -f <file> [options]
```

| Option | Description |
|---|---|
| `-f, --file <path>` | **(Required)** Path to the PDF file to validate |
| `-p, --policy <path>` | Path to a custom ETSI validation policy XML file |
| `--profile <name>` | Use a named configuration profile for this operation |
| `-d, --detailed` | Show low-level details: DSS IDs, key usages, fingerprints, TSA info, resolved config |

Supports all [config overrides](#config-overrides).

**Examples:**

```shell
omnisign validate -f contract.pdf
omnisign validate -f contract.pdf --detailed
omnisign validate -f contract.pdf --profile university --validation-policy CUSTOM -p policy.xml
```

---

### `sign` — Sign a PDF document

```
omnisign sign -f <input> -o <output> [options]
```

| Option | Description |
|---|---|
| `-f, --file <path>` | **(Required)** Path to the input PDF |
| `-o, --output <path>` | **(Required)** Path for the signed output PDF |
| `-c, --certificate <alias>` | Certificate alias to use (see `certificates list`) |
| `-r, --reason <text>` | Reason for signing (embedded in the signature) |
| `--location <text>` | Location of signing (embedded in the signature) |
| `--contact <text>` | Contact information of the signer (embedded in the signature) |
| `--no-timestamp` | Omit the RFC 3161 timestamp — produces B-B instead of B-T or higher |
| `--profile <name>` | Use a named configuration profile for this operation |
| `--visible` | Add a visible signature appearance |
| `--vis-page <n>` | Page for the visible signature (default: `1`) |
| `--vis-x <n>` | X position in PDF user units (required with `--visible`) |
| `--vis-y <n>` | Y position in PDF user units (required with `--visible`) |
| `--vis-width <n>` | Width in PDF user units (required with `--visible`) |
| `--vis-height <n>` | Height in PDF user units (required with `--visible`) |
| `--vis-text <text>` | Custom text inside the visible signature |
| `--vis-image <path>` | Path to an image for the visible signature |

Supports all [config overrides](#config-overrides).

**Examples:**

```shell
# Sign at the default level (B-T by default) using the first available certificate
omnisign sign -f thesis.pdf -o thesis-signed.pdf

# Sign at B-LTA level with a specific certificate and a visible signature
omnisign sign -f thesis.pdf -o thesis-signed.pdf \
  -c "My Qualified Certificate" \
  --signature-level PADES_BASELINE_LTA \
  --reason "Author signature" \
  --visible --vis-x 50 --vis-y 700 --vis-width 200 --vis-height 50

# Sign without a timestamp (B-B only)
omnisign sign -f doc.pdf -o doc-signed.pdf --no-timestamp
```

---

### `timestamp` — Extend a signature to a higher PAdES level

```
omnisign timestamp -f <input> -o <output> [options]
```

Supported promotion paths: **B-B → B-T → B-LT → B-LTA → B-LTA** (archival renewal).

| Option | Description |
|---|---|
| `-f, --file <path>` | **(Required)** Path to the signed input PDF |
| `-o, --output <path>` | **(Required)** Path for the extended output PDF |
| `-l, --level <level>` | Target PAdES level: `PADES_BASELINE_T`, `PADES_BASELINE_LT`, `PADES_BASELINE_LTA` (default: `PADES_BASELINE_T`) |
| `--profile <name>` | Use a named configuration profile for this operation |

Supports all [config overrides](#config-overrides).

**Examples:**

```shell
# Add a document timestamp (B-B → B-T)
omnisign timestamp -f signed.pdf -o signed-t.pdf

# Embed revocation data (B-T → B-LT)
omnisign timestamp -f signed-t.pdf -o signed-lt.pdf --level PADES_BASELINE_LT

# Add archival timestamp (B-LT → B-LTA)
omnisign timestamp -f signed-lt.pdf -o signed-lta.pdf --level PADES_BASELINE_LTA
```

---

### `certificates` — Discover available certificates

#### `certificates list`

Lists all certificates available from configured token sources (PKCS#12 files, PKCS#11 hardware tokens,
Windows Certificate Store, macOS Keychain, etc.).

The **alias** shown here is the value to pass to `sign --certificate <alias>`.

```shell
omnisign certificates list
```

---

### `config` — Manage configuration

Configuration is stored in a plain-text file in the user's home directory.
TSA passwords are stored exclusively in the OS-native credential store (keychain) and are never written to disk.

#### `config show`

Print the current configuration including global defaults, active profile, and all named profiles.

```shell
omnisign config show
```

#### `config set`

Modify the global (default) configuration. Only provided options are updated.

```
omnisign config set [options]
```

| Option | Description |
|---|---|
| `-H, --hash-algorithm <alg>` | Default hash algorithm (`SHA256`, `SHA384`, `SHA512`, …) |
| `-L, --signature-level <level>` | Default signature level (`PADES_BASELINE_B/T/LT/LTA`) |
| `--timestamp-url <url>` | Default timestamp server (TSA) URL |
| `--timestamp-username <user>` | Default TSA HTTP Basic username |
| `--timestamp-password <pass>` | Default TSA HTTP Basic password (stored in OS keychain) |
| `--timestamp-timeout <ms>` | Default TSA request timeout in milliseconds |
| `--ocsp-url <url>` | Default OCSP responder URL |
| `--validation-policy <type>` | Default validation policy (`DEFAULT_ETSI`, `CUSTOM`, …) |
| `--check-revocation <bool>` | Enable/disable certificate revocation checking |
| `--use-eu-lotl <bool>` | Enable/disable the EU List of Trusted Lists |

**Example:**

```shell
omnisign config set \
  --hash-algorithm SHA512 \
  --signature-level PADES_BASELINE_LTA \
  --timestamp-url https://tsa.example.com/tsr \
  --use-eu-lotl true
```

#### `config profile` — Manage named profiles

Profiles let you store sets of configuration values that can be activated per-operation with `--profile <name>`.

| Subcommand | Description |
|---|---|
| `config profile list` | List all profiles |
| `config profile create <name> [options]` | Create or replace a profile |
| `config profile edit <name> [options]` | Update specific fields of an existing profile |
| `config profile use <name>` | Set a profile as the default active profile |
| `config profile remove <name>` | Delete a profile |

`create` and `edit` accept the same options as `config set` plus `--description`.

**Examples:**

```shell
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
```

#### `config tl` — Manage custom trusted lists

Register custom ETSI Trusted List sources for signature validation.

| Subcommand | Description |
|---|---|
| `config tl add` | Register a custom trusted list source (HTTPS URL or local file) |
| `config tl list` | List all registered custom trusted lists |
| `config tl remove <name>` | Remove a registered custom trusted list |
| `config tl build` | Build a custom trusted list interactively or edit a draft |

**`config tl add` options:**

| Option | Description |
|---|---|
| `-n, --name <name>` | **(Required)** Unique label for this trusted list |
| `-s, --source <url/path>` | **(Required)** HTTPS URL or file path to the TL XML |
| `--signing-cert <path>` | PEM/DER certificate to verify the TL's XML signature (strongly recommended) |
| `-p, --profile <name>` | Store in the given profile instead of the global config |

**`config tl build` subcommands:**

| Subcommand | Description |
|---|---|
| `config tl build create <name>` | Guided interactive wizard to build a complete trusted list draft |
| `config tl build show <name>` | Show the contents of an existing draft |
| `config tl build add-tsp <draft> <tsp-name>` | Add a Trust Service Provider to a draft |
| `config tl build remove-tsp <draft> <tsp-name>` | Remove a TSP from a draft |
| `config tl build add-service <draft> <tsp-name>` | Add a trust service to a TSP in a draft |
| `config tl build remove-service <draft> <tsp-name> <service-name>` | Remove a service from a TSP |
| `config tl build compile <draft>` | Compile a draft to a TL XML and optionally register it |
| `config tl build delete <draft>` | Delete a draft |

---

### Config Overrides

The following options are available on `validate`, `sign`, and `timestamp` to override
configuration for a single execution without modifying any stored settings.
TSA credentials supplied here are held in memory only and are never written to disk.

| Option | Description |
|---|---|
| `-H, --hash-algorithm <alg>` | Hash algorithm override |
| `-L, --signature-level <level>` | Signature level override |
| `--timestamp-url <url>` | TSA URL override |
| `--timestamp-username <user>` | TSA HTTP Basic username override |
| `--timestamp-password <pass>` | TSA HTTP Basic password override (in-memory only) |
| `--timestamp-timeout <ms>` | TSA request timeout override in milliseconds |
| `--validation-policy <type>` | Validation policy type override |
| `--no-global-tls` | Exclude global trusted lists; use only profile-level TLs |

## Exit Codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Command error (invalid arguments, missing required options, etc.) |
| non-zero | Operational failure (e.g. signing error, validation error) — details printed to stderr |

