---
sidebar_position: 3
---

import AppIcon from '@site/src/components/AppIcon';

# Settings

The Settings dialog configures global defaults for all signing and validation operations.
Open it via the **gear icon** <AppIcon name="settings" label="Settings" /> in the toolbar.

The dialog uses an IntelliJ-style layout: a collapsible category tree on the left and a
content panel on the right. Clicking a group header selects its first child; clicking a
leaf shows the corresponding form.

![Settings dialog](/img/desktop/settings-overview.avif)

## Settings categories

### Signing

#### Defaults

Configure the default hash algorithm, encryption algorithm, and signature level used
when signing documents. These defaults can be overridden per-profile or per-operation.
See [Algorithms](../algorithms.md) for the full list of supported algorithms.

| Setting              | Description                                                                                                     | Default |
|----------------------|-----------------------------------------------------------------------------------------------------------------|---------|
| Hash algorithm       | Digest algorithm for the signature (see [full list](../algorithms.md#hash-algorithms))                          | SHA-256 |
| Encryption algorithm | Signing key algorithm (see [full list](../algorithms.md#encryption-algorithms); auto-detected from certificate) | Auto    |
| Signature timestamp  | Include a signature timestamp and revocation data (B-LT)                                                        | Off     |
| Archival timestamp   | Include an archival document timestamp (B-LTA)                                                                  | Off     |
| Allow signing with an expired certificate | Produce a signature even if the signing certificate has expired. **Such signatures fail validation**, so leave off unless you specifically need it | Off |

The effective PAdES level is derived from the two timestamp checkboxes: neither → B-B,
signature only → B-LT, both → B-LTA.

#### Disabled Algorithms

Algorithms disabled here cannot be selected at any level — profiles and individual
operations that reference a disabled algorithm are rejected during configuration
resolution. Separate lists are maintained for hash algorithms and encryption algorithms.

### Services

#### Timestamp Server

Configure the RFC 3161 timestamp server (TSA) used for B-T, B-LT, and B-LTA signatures.

| Setting  | Description                                                        |
|----------|--------------------------------------------------------------------|
| TSA URL  | HTTPS endpoint of the timestamp authority                          |
| Username | HTTP Basic authentication username (if required)                   |
| Password | HTTP Basic authentication password                                 |
| Timeout  | Request timeout in milliseconds (default: 30,000)                  |

:::note
The TSA password is stored in the operating system's native credential store
(Windows Credential Manager, macOS Keychain, or libsecret on Linux) — it is never
written to the configuration file. When a password has been previously stored, the
field shows a placeholder indicator.
:::

#### OCSP & CRL

Configure connection timeouts for Online Certificate Status Protocol and Certificate
Revocation List requests used during validation and B-LT/B-LTA signing.

| Setting      | Description                                     | Default |
|--------------|-------------------------------------------------|---------|
| OCSP timeout | OCSP request timeout in milliseconds            | 30,000  |
| CRL timeout  | CRL request timeout in milliseconds             | 30,000  |

### Validation

#### Policy & Trust

- **Validation policy** — choose between the default ETSI policy or a custom XML policy
  file. When "Custom" is selected, enter the file path to the XML policy document.
- **Revocation checking** — enable or disable CRL/OCSP checking during validation.
- **EU LOTL** — toggle integration with the EU List of Trusted Lists. When enabled,
  OmniSign loads the EU LOTL at startup and uses it for certificate qualification
  and trust chain resolution.
- **Alert if not on EU LOTL** — when on, the validation panel flags every signature whose trust
  anchor is not on the EU LOTL with a red crossed EU emblem (per signature and in the overall
  badge). It requires **EU LOTL** to be enabled: when EU LOTL is off, the switch is disabled and
  shows an info hint, and its value is preserved so re-enabling EU LOTL restores it. See
  [Validating Signatures → EU LOTL membership](validation.md#eu-lotl-membership).
- **Trusted list refresh interval (hours)** — how often the EU LOTL and custom lists are
  refreshed automatically in the background (minimum 1 hour).
- **Refresh now** — a refresh button, with a "Last refreshed" indicator, forces an immediate
  online refresh of the EU LOTL and every custom list into the shared cache. While the refresh
  runs, a **Loading trusted lists…** progress bar appears below the row — indeterminate until the
  member-state and custom lists are known, then a determinate *"loaded of N"* count.

:::note
While a trusted list the active configuration depends on is being refreshed, the validation
panel's refresh action is temporarily disabled until it completes.
:::

#### Algorithm Constraints

Control how the validator reacts when a cryptographic algorithm has passed its
ETSI TS 119 312 expiration date. Two separate settings are available:

| Setting                                 | Description                                          | Default |
|-----------------------------------------|------------------------------------------------------|---------|
| Expiration (before policy update date)  | Severity when the algorithm expired before the date  | FAIL    |
| Expiration (after policy update date)   | Severity when the algorithm expired after the date   | WARN    |

Available severity levels: **FAIL** (validation fails), **WARN** (reported as a warning), and
**INFORM** (reported as an informational note in the report, with no effect on the overall result).
A fourth level, **IGNORE**, skips the check silently with no message; it is intentionally not offered
in the app — so an expired algorithm is never hidden without a trace — and is reserved for config
files (CLI / server).

#### Trusted Certificates

Add CA and TSA certificates that should be directly trusted during validation, without
requiring a full ETSI Trusted List XML document. They are wired into DSS alongside any ETSI
trusted lists and apply to the **global** scope; profile-scoped certificates are managed in the
[profile editor](profiles.md#trusted-certificates).

For each certificate, choose a **trust role** — **CA**, **TSA**, or **Any** (trusted for both) —
then pick a `.pem` / `.der` / `.crt` / `.cer` file (or type its path). Additions and removals are
**staged**: a new certificate shows a **Pending** badge and a removed one stays visible marked
**Removing** (with an undo button) until you click **Save**; closing the dialog without saving
discards the staged changes. Adding a certificate whose fingerprint is already trusted in this
scope is rejected. The read-only [Trusted Certificates panel](trusted-certificates.md) shows the
resulting global and active-profile certificates.

![Global Trusted Certificates settings](/img/desktop/settings-trusted-certs.avif)

#### Trusted Lists

Register external ETSI TS 119612 Trusted List XML sources. Each entry may be an HTTPS URL
or a local file path. An optional signing certificate verifies the TL's XML signature —
strongly recommended for non-EU lists.

A **Build** button opens the [Trusted List builder](tl-builder.md) dialog for creating new
TL documents from scratch.

### Tokens / PKCS#11 Libraries

Register custom PKCS#11 middleware library paths for hardware token discovery. OmniSign
auto-detects common middleware on supported platforms; use this section to add libraries
that are not discovered automatically.

- **Probe timeout (seconds)** — the maximum time OmniSign waits for a single PKCS#11 library
  probe before terminating it. Each library is probed in an isolated subprocess, so middleware
  that hangs during initialization cannot stall token discovery; this value bounds that wait.
  Allowed range 1–120 seconds; defaults to 30.

Each entry is the absolute file path to the middleware shared library (`.dll`, `.so`, or
`.dylib`).

Each registered library — and the add-library form — has a **Has its own PIN pad** toggle. Enable it
for middleware that collects the PIN on its own secure pad or on-screen keyboard (some national eID
clients work this way): OmniSign then shows no PIN dialog of its own for that library and lets the
module drive PIN entry, avoiding a double prompt. Leave it off for middleware that accepts the PIN
programmatically.

:::tip Drop directory
This section also shows an auto-discovery **drop directory**. Copy a PKCS#11 library file
(`.dll` / `.so` / `.dylib`) into it and OmniSign discovers it automatically — no manual entry
needed. The path is a clickable link that reveals the folder in your file manager.
:::

![PKCS#11 Libraries settings](/img/desktop/settings-pkcs11.avif)

### Archiving

#### Renewal Jobs

Configure named renewal jobs for automatic archival re-timestamping of B-LTA documents.
Each job defines:

| Field         | Description                                                          |
|---------------|----------------------------------------------------------------------|
| Name          | Unique identifier for the renewal job.                               |
| Glob patterns | File path patterns to watch (e.g. `/data/signed/**/*.pdf`).          |
| Buffer (days) | Re-timestamp when the archival timestamp expires within this window. |
| Profile       | Optional profile whose settings are used for the renewal operation.  |
| Notify        | Whether to send OS notifications on completion or failure.           |

![Renewal Jobs settings](/img/desktop/settings-renewal-jobs.avif)

#### Scheduler

Configure the OS-level daily scheduler that runs renewal jobs automatically.

| Field           | Description                                                                                                                                       |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| Executable path | Path to the OmniSign executable. Auto-detected when running from an installed package; editable as a fallback when auto-detection is unavailable. |
| Run at (hour)   | Hour of the day (0–23) for the daily run (default: 2).                                                                                            |
| Run at (minute) | Minute of the hour (0–59) for the daily run (default: 0).                                                                                         |
| Log file        | Optional append-only log file path for scheduler output.                                                                                          |

![Scheduler settings](/img/desktop/settings-scheduler.avif)

The scheduler uses **Task Scheduler** on Windows, **systemd user timers** on Linux, and
**launchd** on macOS. Each catches up a run missed while the machine was off or asleep,
executing it once as soon as the machine is next available rather than skipping it until the
next day. (On Linux a systemd user instance is required; where none exists — containers, some
WSL setups — OmniSign instead prints the command to schedule renewal with another tool.) The
current installation status is shown as a read-only indicator.

At the scheduled time the scheduler launches OmniSign in **headless renewal mode** (no window):
it checks every configured renewal job, re-timestamps the B-LTA files whose archival timestamp
falls within the job's buffer window, and appends to the scheduler log file when one is set. Jobs
with **Notify** enabled raise an OS notification on completion or failure, so unattended renewals
stay visible. (This is the same batch OmniSign runs when started with the `renew` argument.)

### Backup

#### Import & Export

Export the entire configuration — global settings, every profile, and all referenced trusted
certificates — to a single ZIP archive, or import an archive to replace the current
configuration.

- **Export** <AppIcon name="download" label="Export" /> — prompts for a save location and writes the archive.
- **Import** <AppIcon name="upload" label="Import" /> — asks for confirmation first, because it **replaces all current
  settings, profiles, and trusted certificates and cannot be undone**, then lets you pick the
  archive to restore.

### Appearance (Linux)

#### Window

Choose how the window is framed. This category appears **only on Linux**.

- **Use native title bar** — when on, the toolbar is shown below the native OS title bar; when
  off (the default), the toolbar is merged into a custom header (client-side decoration) with
  custom window controls.

Changing this setting requires an application restart to take effect.

### Language & Region

Choose the application's UI language and how dates are displayed. This category is available on every
platform.

| Field             | Description                                                                                                                                                                                       |
|-------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Region preset** | Sets the language and date format together in one step — *United Kingdom*, *United States*, *Česko*, or *Slovensko*. *System default* follows the OS locale; *Custom* is shown (read-only) when the chosen language and format match no preset. |
| **Language**      | The UI language: *English*, *Čeština*, *Slovenčina*, or *System default* (follows the operating system).                                                                                          |
| **Date format**   | How dates are shown: *System default*, `dd/mm/yyyy`, `dd.mm.yyyy`, `mm/dd/yyyy`, or ISO 8601 (`yyyy-mm-dd`). This preference is shared with the CLI's `config date-format` command.                |

Changes **preview live** as you pick them, **take effect when you save**, and **revert if you close the
dialog without saving**; the saved choice is restored on the next launch. Switching the language also
re-renders the signature validation report in that language.

