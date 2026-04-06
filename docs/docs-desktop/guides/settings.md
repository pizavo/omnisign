---
sidebar_position: 3
---

# Settings

The Settings dialog configures global defaults for all signing and validation operations.
Open it via the **gear icon** (⚙️) in the toolbar.

The dialog uses an IntelliJ-style layout: a collapsible category tree on the left and a
content panel on the right. Clicking a group header selects its first child; clicking a
leaf shows the corresponding form.

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
| Timeout  | Request timeout in milliseconds (default: 30 000)                  |

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
| OCSP timeout | OCSP request timeout in milliseconds            | 30 000  |
| CRL timeout  | CRL request timeout in milliseconds             | 30 000  |

### Validation

#### Policy & Trust

- **Validation policy** — choose between the default ETSI policy or a custom XML policy
  file. When "Custom" is selected, enter the file path to the XML policy document.
- **Revocation checking** — enable or disable CRL/OCSP checking during validation.
- **EU LOTL** — toggle integration with the EU List of Trusted Lists. When enabled,
  OmniSign loads the EU LOTL at startup and uses it for certificate qualification
  and trust chain resolution.

#### Algorithm Constraints

Control how the validator reacts when a cryptographic algorithm has passed its
ETSI TS 119 312 expiration date. Two separate settings are available:

| Setting                                 | Description                                          | Default |
|-----------------------------------------|------------------------------------------------------|---------|
| Expiration (before policy update date)  | Severity when the algorithm expired before the date  | FAIL    |
| Expiration (after policy update date)   | Severity when the algorithm expired after the date   | WARN    |

Available severity levels: **FAIL**, **WARN**, **INFORM**, **IGNORE**.

#### Trusted Certificates

Add CA and TSA certificates that should be directly trusted during validation, without
requiring a full ETSI Trusted List XML document. Click the Add button and select a
PEM or DER certificate file using the file picker.

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

Each entry is the absolute file path to the middleware shared library (`.dll`, `.so`, or
`.dylib`).

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

#### Scheduler

Configure the OS-level daily scheduler that runs renewal jobs automatically.

| Field           | Description                                                                                                                                       |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| Executable path | Path to the OmniSign executable. Auto-detected when running from an installed package; editable as a fallback when auto-detection is unavailable. |
| Run at (hour)   | Hour of the day (0–23) for the daily run (default: 2).                                                                                            |
| Run at (minute) | Minute of the hour (0–59) for the daily run (default: 0).                                                                                         |
| Log file        | Optional append-only log file path for scheduler output.                                                                                          |

The scheduler uses **Task Scheduler** on Windows, **cron** on Linux, and **launchd** on
macOS. The current installation status is shown as a read-only indicator.

