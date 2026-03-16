# OmniSign CLI

Command-line interface for **OmniSign** — a multiplatform digital signature tool built on the
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

Or, when using the `install` distribution:

```shell
# Linux / macOS
./cli/build/install/cli/bin/cli <command> [options]

# Windows
.\cli\build\install\cli\bin\cli.bat <command> [options]
```

## Installation

### Windows — MSI installer

The MSI installer registers `omnisign` in PATH automatically.
During installation a dialog lets you choose between a system-wide install
(Program Files, system PATH, requires elevation) and a per-user install
(LocalAppData, user PATH, no elevation).

### Linux — DEB / RPM

Both packages install the application to `/opt/omnisign/` and create a
`/usr/local/bin/omnisign` symlink so the command is available immediately.

```shell
# Debian / Ubuntu
sudo dpkg -i omnisign-cli-<version>.deb

# Fedora / RHEL
sudo rpm -i omnisign-cli-<version>.rpm
```

### macOS — PKG installer (recommended)

The `.pkg` installer places the application in `/Applications/omnisign.app`
and creates a `/usr/local/bin/omnisign` symlink so the command is available
in every new terminal session. It also installs an `omnisign-uninstall`
helper (see [Uninstalling on macOS](#uninstalling-on-macos)).

### macOS — DMG disk image

The `.dmg` is a drag-to-Applications disk image and **does not** register
`omnisign` in PATH. If you prefer the DMG, create the symlink manually:

```shell
ln -s /Applications/omnisign.app/Contents/MacOS/omnisign /usr/local/bin/omnisign
```

Or use the `.pkg` installer instead.

### Portable app-images (Windows / Linux)

App-image archives do not modify PATH. Run the binary directly from the
extracted directory or add it to PATH manually.

### macOS Gatekeeper note

The macOS packages are currently unsigned. On first launch macOS may show an
"unidentified developer" warning. You can bypass it with:

```shell
xattr -cr /Applications/omnisign.app
```

or by right-clicking the application and choosing **Open**.
Code signing requires an Apple Developer ID certificate
(separate from PGP/X.509, obtained through the
[Apple Developer Program](https://developer.apple.com/programs/)).

### Uninstalling on macOS

The `.pkg` installer places an `omnisign-uninstall` command in
`/usr/local/bin`. Running it removes the application, the PATH symlink,
and the uninstaller itself:

```shell
omnisign-uninstall
```

To uninstall manually (e.g. after a DMG install):

```shell
rm -f /usr/local/bin/omnisign
rm -rf /Applications/omnisign.app
```

## Environment Variables

All options can also be set via environment variables using the `OMNISIGN_` prefix.
For example, `--timestamp-url` becomes `OMNISIGN_TIMESTAMP_URL`.
Explicit command-line options always take precedence over environment variables.

## Command Reference

### Top-level

```
omnisign [global-flags] [command]
```

| Global flag     | Description                                                      |
|-----------------|------------------------------------------------------------------|
| `--json`        | Emit machine-readable JSON output instead of human-readable text |
| `--verbose`     | Enable verbose (DEBUG-level) logging to stderr                   |
| `--quiet`       | Suppress all informational output; only errors are printed       |
| `-v, --version` | Print the application version and exit                           |

| Command        | Description                                                        |
|----------------|--------------------------------------------------------------------|
| `validate`     | Validate a signed PDF document                                     |
| `sign`         | Sign a PDF document with a PAdES digital signature                 |
| `timestamp`    | Extend a signed PDF to a higher PAdES level                        |
| `renew`        | Execute configured renewal jobs (re-timestamp expiring B-LTA PDFs) |
| `algorithms`   | List supported cryptographic algorithms                            |
| `certificates` | Discover and inspect available signing certificates                |
| `config`       | Manage application configuration                                   |
| `schedule`     | Manage the automatic re-timestamping scheduler and renewal jobs    |

---

### `validate` — Validate a signed PDF

```
omnisign validate -f <file> [options]
```

| Option                     | Description                                                                                                                          |
|----------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `-f, --file <path>`        | **(Required)** Path to the PDF file to validate                                                                                      |
| `-p, --policy <path>`      | Path to a custom ETSI validation policy XML file                                                                                     |
| `--profile <name>`         | Use a named configuration profile for this operation                                                                                 |
| `-d, --detailed`           | Show detailed output including DSS IDs, key usages, timestamp IDs, and resolved configuration                                        |
| `--report-out <path>`      | Write the raw DSS validation report to this file                                                                                     |
| `--report-format <format>` | Format of the report written by `--report-out` (`XML_DETAILED`, `XML_SIMPLE`, `XML_DIAGNOSTIC`, `XML_ETSI`). Default: `XML_DETAILED` |

Supports all [config overrides](#config-overrides).

**Examples:**

```shell
omnisign validate -f contract.pdf
omnisign validate -f contract.pdf --detailed
omnisign validate -f contract.pdf --report-out report.xml --report-format XML_SIMPLE
omnisign validate -f contract.pdf --profile university --validation-policy CUSTOM -p policy.xml
```

---

### `sign` — Sign a PDF document

```
omnisign sign -f <input> -o <output> [options]
```

| Option                      | Description                                                         |
|-----------------------------|---------------------------------------------------------------------|
| `-f, --file <path>`         | **(Required)** Path to the input PDF                                |
| `-o, --output <path>`       | **(Required)** Path for the signed output PDF                       |
| `-c, --certificate <alias>` | Certificate alias to use (see `certificates list`)                  |
| `-r, --reason <text>`       | Reason for signing (embedded in the signature)                      |
| `--location <text>`         | Location of signing (embedded in the signature)                     |
| `--contact <text>`          | Contact information of the signer (embedded in the signature)       |
| `--no-timestamp`            | Omit the RFC 3161 timestamp — produces B-B instead of B-T or higher |
| `--profile <name>`          | Use a named configuration profile for this operation                |
| `--visible`                 | Add a visible signature appearance                                  |
| `--vis-page <n>`            | Page for the visible signature (default: `1`)                       |
| `--vis-x <n>`               | X position in PDF user units (required with `--visible`)            |
| `--vis-y <n>`               | Y position in PDF user units (required with `--visible`)            |
| `--vis-width <n>`           | Width in PDF user units (required with `--visible`)                 |
| `--vis-height <n>`          | Height in PDF user units (required with `--visible`)                |
| `--vis-text <text>`         | Custom text inside the visible signature                            |
| `--vis-image <path>`        | Path to an image for the visible signature                          |

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

| Option                | Description                                                                                                     |
|-----------------------|-----------------------------------------------------------------------------------------------------------------|
| `-f, --file <path>`   | **(Required)** Path to the signed input PDF                                                                     |
| `-o, --output <path>` | **(Required)** Path for the extended output PDF                                                                 |
| `-l, --level <level>` | Target PAdES level: `PADES_BASELINE_T`, `PADES_BASELINE_LT`, `PADES_BASELINE_LTA` (default: `PADES_BASELINE_T`) |
| `--profile <name>`    | Use a named configuration profile for this operation                                                            |

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

### `renew` — Run archival renewal jobs

```
omnisign renew [options]
```

Checks each B-LTA PDF tracked by the configured renewal jobs and re-timestamps
in-place any file whose archival timestamp is nearing expiry.
Intended to be invoked daily by the OS scheduler registered via `schedule install`,
but can also be run manually at any time.

| Option             | Description                                                            |
|--------------------|------------------------------------------------------------------------|
| `-j, --job <name>` | Run only the named renewal job. Runs all configured jobs when omitted. |
| `--dry-run`        | Report which files need renewal without modifying any file.            |

**Examples:**

```shell
# Run all renewal jobs
omnisign renew

# Dry-run to see what would be renewed
omnisign renew --dry-run

# Run a single named job
omnisign renew --job university
```

---

### `algorithms` — List supported cryptographic algorithms

#### `algorithms hash list`

Lists all supported hash algorithms with their ETSI TS 119 312 expiration dates and usage notes.
The algorithm name shown here is the value accepted by `--hash-algorithm` throughout the CLI.

```shell
omnisign algorithms hash list
```

#### `algorithms encryption list`

Lists all supported encryption (signing key) algorithms.
The algorithm name shown here is the value accepted by `--encryption-algorithm` throughout the CLI.

```shell
omnisign algorithms encryption list
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

#### `config path`

Print the resolved configuration file path. Useful for scripting or debugging.

```shell
omnisign config path
```

#### `config set`

Modify the global (default) configuration. Only provided options are updated.

```
omnisign config set [options]
```

| Option                                         | Description                                                                       |
|------------------------------------------------|-----------------------------------------------------------------------------------|
| `-H, --hash-algorithm <alg>`                   | Default hash algorithm (`SHA256`, `SHA384`, `SHA512`, …)                          |
| `-E, --encryption-algorithm <alg>`             | Default encryption (signing key) algorithm                                        |
| `-L, --signature-level <level>`                | Default signature level (`PADES_BASELINE_B/T/LT/LTA`)                             |
| `--timestamp-url <url>`                        | Default timestamp server (TSA) URL                                                |
| `--timestamp-username <user>`                  | Default TSA HTTP Basic username                                                   |
| `--timestamp-password <pass>`                  | Default TSA HTTP Basic password (stored in OS keychain)                           |
| `--timestamp-timeout <ms>`                     | Default TSA request timeout in milliseconds                                       |
| `--ocsp-url <url>`                             | Default OCSP responder URL                                                        |
| `--validation-policy <type>`                   | Default validation policy (`DEFAULT_ETSI`, `CUSTOM`, …)                           |
| `--check-revocation <bool>`                    | Enable/disable certificate revocation checking                                    |
| `--use-eu-lotl <bool>`                         | Enable/disable the EU List of Trusted Lists                                       |
| `--algo-expiration-level <level>`              | Severity when an algorithm's expiration date has passed                           |
| `--algo-expiration-level-after-update <level>` | Severity after the policy update date                                             |
| `--algo-expiry-override <ALG=DATE>`            | Per-algorithm expiration date override (e.g. `RIPEMD160=2030-01-01`). Repeatable. |
| `--disable-hash-algorithm <alg>`               | Globally disable a hash algorithm so it cannot be selected. Repeatable.           |
| `--enable-hash-algorithm <alg>`                | Re-enable a globally disabled hash algorithm. Repeatable.                         |
| `--disable-encryption-algorithm <alg>`         | Globally disable an encryption algorithm. Repeatable.                             |
| `--enable-encryption-algorithm <alg>`          | Re-enable a globally disabled encryption algorithm. Repeatable.                   |

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

`create` accepts `--description`, `--hash-algorithm`, `--encryption-algorithm`, `--signature-level`,
`--timestamp-url`, `--timestamp-username`, `--timestamp-password`, `--timestamp-timeout`,
`--validation-policy`, `--algo-expiration-level`, `--algo-expiration-level-after-update`,
`--algo-expiry-override` (repeatable), `--disable-hash-algorithm` (repeatable),
and `--disable-encryption-algorithm` (repeatable).

`edit` accepts the same options as `create` plus the following additional options:

| Option                                | Effect                                                                       |
|---------------------------------------|------------------------------------------------------------------------------|
| `--enable-hash-algorithm <alg>`       | Remove a hash algorithm from this profile's disabled set. Repeatable.        |
| `--enable-encryption-algorithm <alg>` | Remove an encryption algorithm from this profile's disabled set. Repeatable. |

And the following `--clear-*` flags to explicitly unset individual fields:

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

`export` options:

| Option               | Description                                                                       |
|----------------------|-----------------------------------------------------------------------------------|
| `<name>`             | **(Required)** Name of the profile to export                                      |
| `<output-file>`      | **(Required)** Destination file path                                              |
| `-f, --format <fmt>` | Export format (`JSON`, `XML`, `YAML`). Inferred from file extension when omitted. |

`import` options:

| Option               | Description                                                                               |
|----------------------|-------------------------------------------------------------------------------------------|
| `<input-file>`       | **(Required)** Source file path                                                           |
| `-f, --format <fmt>` | Import format (`JSON`, `XML`, `YAML`). Inferred from file extension when omitted.         |
| `-n, --name <name>`  | Override the profile name from the file. Uses the name embedded in the file when omitted. |

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

# Show the active profile
omnisign config profile show

# Show a specific profile
omnisign config profile show university

# Sign using the profile explicitly
omnisign sign -f thesis.pdf -o thesis-signed.pdf --profile university

# Update the TSA URL in an existing profile
omnisign config profile edit university --timestamp-url https://new-tsa.example.com/tsr

# Remove the timestamp config from a profile (fall back to global)
omnisign config profile edit university --clear-timestamp

# Export a profile to share it
omnisign config profile export university university.json

# Import a profile on another machine
omnisign config profile import university.json

# Import and rename
omnisign config profile import university.json --name university-backup
```

#### `config export`

Export the global configuration section (or the full application configuration) to a file.
The format is inferred from the output file extension when `--format` is omitted.

```
omnisign config export <output-file> [options]
```

| Option               | Description                                                                       |
|----------------------|-----------------------------------------------------------------------------------|
| `<output-file>`      | **(Required)** Destination file path                                              |
| `-f, --format <fmt>` | Export format (`JSON`, `XML`, `YAML`). Inferred from file extension when omitted. |
| `-a, --all`          | Export the full application configuration instead of only the global section.     |

**Examples:**

```shell
omnisign config export global.json
omnisign config export global.yaml --format YAML
omnisign config export backup.json --all
```

#### `config import`

Import a global configuration section (or the full application configuration) from a file,
replacing the corresponding stored values.

```
omnisign config import <input-file> [options]
```

| Option               | Description                                                                       |
|----------------------|-----------------------------------------------------------------------------------|
| `<input-file>`       | **(Required)** Source file path                                                   |
| `-f, --format <fmt>` | Import format (`JSON`, `XML`, `YAML`). Inferred from file extension when omitted. |
| `-a, --all`          | Import as a full application configuration, replacing all sections.               |

**Examples:**

```shell
omnisign config import global.json
omnisign config import backup.json --all
```

#### `config tl` — Manage custom trusted lists

Register custom ETSI Trusted List sources for signature validation.

| Subcommand                | Description                                                     |
|---------------------------|-----------------------------------------------------------------|
| `config tl add`           | Register a custom trusted list source (HTTPS URL or local file) |
| `config tl list`          | List all registered custom trusted lists                        |
| `config tl remove <name>` | Remove a registered custom trusted list                         |
| `config tl build`         | Build a custom trusted list interactively or edit a draft       |

**`config tl add` options:**

| Option                    | Description                                                                 |
|---------------------------|-----------------------------------------------------------------------------|
| `-n, --name <name>`       | **(Required)** Unique label for this trusted list                           |
| `-s, --source <url/path>` | **(Required)** HTTPS URL or file path to the TL XML                         |
| `--signing-cert <path>`   | PEM/DER certificate to verify the TL's XML signature (strongly recommended) |
| `-p, --profile <name>`    | Store in the given profile instead of the global config                     |

**`config tl build` subcommands:**

| Subcommand                                                           | Description                                                      |
|----------------------------------------------------------------------|------------------------------------------------------------------|
| `config tl build create <name>`                                      | Guided interactive wizard to build a complete trusted list draft |
| `config tl build show <name>`                                        | Show the contents of an existing draft                           |
| `config tl build tsp add <draft> <tsp-name>`                         | Add a Trust Service Provider to a draft                          |
| `config tl build tsp remove <draft> <tsp-name>`                      | Remove a TSP from a draft                                        |
| `config tl build service add <draft> <tsp-name>`                     | Add a trust service to a TSP in a draft                          |
| `config tl build service remove <draft> <tsp-name> <service-name>`   | Remove a service from a TSP                                      |
| `config tl build compile <draft>`                                    | Compile a draft to a TL XML and optionally register it           |
| `config tl build delete <draft>`                                     | Delete a draft                                                   |

#### `config pkcs11` — Manage custom PKCS#11 middleware libraries

Register custom PKCS#11 middleware library paths for token discovery.
Libraries registered here are merged into token discovery alongside the OS-native
autodiscovery results and the built-in fallback candidate list.

| Subcommand                    | Description                                             |
|-------------------------------|---------------------------------------------------------|
| `config pkcs11 add`           | Register a custom PKCS#11 middleware library            |
| `config pkcs11 list`          | List all registered custom PKCS#11 middleware libraries |
| `config pkcs11 remove <name>` | Remove a registered PKCS#11 middleware library          |

**`config pkcs11 add` options:**

| Option              | Description                                                                            |
|---------------------|----------------------------------------------------------------------------------------|
| `-n, --name <name>` | **(Required)** Unique label for this library (used in `pkcs11 remove`)                 |
| `-p, --path <path>` | **(Required)** Absolute path to the PKCS#11 shared library (`.dll` / `.so` / `.dylib`) |

**Examples:**

```shell
# Register a PKCS#11 library installed in a non-standard location
omnisign config pkcs11 add --name safenet --path /usr/lib/libeTPkcs11.so

# List registered libraries
omnisign config pkcs11 list

# Remove a registration
omnisign config pkcs11 remove safenet
```

---

### `schedule` — Manage the OS renewal scheduler

Controls the OS-level daily job that invokes `omnisign renew` automatically, and manages
the [RenewalJob](#schedule-job--manage-renewal-jobs) entries stored in the configuration.

#### `schedule install`

Register (or replace) the daily `omnisign renew` job with the OS scheduler
(cron on Linux/macOS, Task Scheduler on Windows).

```
omnisign schedule install [options]
```

| Option              | Description                                                                                              |
|---------------------|----------------------------------------------------------------------------------------------------------|
| `--cli-path <path>` | Absolute path to the omnisign binary. Auto-detected when omitted (may fail for `java -jar` invocations). |
| `--hour <0-23>`     | Hour of day at which the job runs. Default: `2`                                                          |
| `--minute <0-59>`   | Minute at which the job runs. Default: `0`                                                               |
| `--log-file <path>` | Absolute path to an append-only log file for renewal output.                                             |

#### `schedule uninstall`

Remove the daily `omnisign renew` OS job if it exists.

```shell
omnisign schedule uninstall
```

#### `schedule status`

Show whether the daily `omnisign renew` OS job is currently registered.

```shell
omnisign schedule status
```

#### `schedule job` — Manage renewal jobs

Renewal jobs define which PDF files are monitored for archival timestamp expiry and
with which settings (TSA profile, buffer window, notification behavior).

| Subcommand                   | Description                      |
|------------------------------|----------------------------------|
| `schedule job add <name>`    | Add or replace a renewal job     |
| `schedule job list`          | List all configured renewal jobs |
| `schedule job remove <name>` | Remove a renewal job             |

**`schedule job add` options:**

| Option                  | Description                                                                                              |
|-------------------------|----------------------------------------------------------------------------------------------------------|
| `-g, --glob <pattern>`  | **(Required, repeatable)** Glob pattern matching PDF files to watch                                      |
| `-b, --buffer-days <n>` | Days before timestamp certificate expiry at which re-timestamping is triggered. Default: library default |
| `--profile <name>`      | Named configuration profile to use for TSA and revocation settings                                       |
| `--log-file <path>`     | Absolute path to an append-only log file for this job's renewal output                                   |
| `--no-notify`           | Disable OS desktop notifications for this job (recommended for headless deployments)                     |

**Examples:**

```shell
# Add a renewal job that watches all PDFs under a directory
omnisign schedule job add thesis-archive \
  --glob "/home/user/docs/**/*.pdf" \
  --buffer-days 60 \
  --profile university

# Install the daily OS scheduler job
omnisign schedule install --hour 3 --log-file /var/log/omnisign-renew.log

# Check scheduler status
omnisign schedule status
```

---

### Config Overrides

The following options are available on `validate`, `sign`, and `timestamp` to override
configuration for a single execution without modifying any stored settings.
TSA credentials supplied here are held in memory only and are never written to disk.

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

## Exit Codes

| Code     | Meaning                                                                                 |
|----------|-----------------------------------------------------------------------------------------|
| `0`      | Success                                                                                 |
| `1`      | Command error (invalid arguments, missing required options, etc.)                       |
| non-zero | Operational failure (signing error, validation error, etc.) — details printed to stderr |
