---
sidebar_position: 3
---

# Settings

The Settings dialog configures global defaults for all signing and validation operations.
Open it via the **gear icon** (⚙️) in the toolbar.

## Settings categories

### Signing Defaults

Configure the default hash algorithm, encryption algorithm, and signature level used
when signing documents. These defaults can be overridden per-profile or per-operation.

| Setting              | Description                                            | Default |
|----------------------|--------------------------------------------------------|---------|
| Hash algorithm       | Digest algorithm for the signature                     | SHA-256 |
| Encryption algorithm | Signing key algorithm (auto-detected from certificate) | Auto    |
| Signature level      | PAdES baseline level (B, B-T, B-LT, B-LTA)             | B-B     |

### Timestamp Server

Configure the RFC 3161 timestamp server (TSA) used for B-T, B-LT, and B-LTA signatures.

| Setting  | Description                                                        |
|----------|--------------------------------------------------------------------|
| TSA URL  | HTTPS endpoint of the timestamp authority                          |
| Username | HTTP Basic authentication username (if required)                   |
| Password | HTTP Basic authentication password (stored in OS credential store) |
| Timeout  | Request timeout in milliseconds                                    |

### Services (OCSP / CRL)

Configure connection timeouts for Online Certificate Status Protocol and Certificate
Revocation List requests used during validation and B-LT/B-LTA signing.

### Validation

#### Policy & Trust

- **Validation policy** — choose between the default ETSI policy or a custom XML policy
- **Revocation checking** — enable or disable CRL/OCSP checking
- **EU LOTL** — toggle integration with the EU List of Trusted Lists

#### Algorithm Constraints

Control how the validator reacts when a cryptographic algorithm has passed its
ETSI TS 119 312 expiration date. Severity levels: `FAIL`, `WARN`, `INFORM`, `IGNORE`.

#### Trusted Certificates

Add CA and TSA certificates that should be directly trusted during validation, without
requiring a full ETSI Trusted List XML document.

#### Trusted Lists

Register external ETSI TS 119612 Trusted List XML sources. Each entry may be an HTTPS URL
or a local file path. An optional signing certificate verifies the TL's XML signature.

### Tokens / PKCS#11 Libraries

Register custom PKCS#11 middleware library paths for hardware token discovery.

### Archiving / Renewal Jobs

Configure automatic archival renewal of B-LTA documents. Define glob patterns
to watch and the re-timestamping buffer window.

### Scheduler

View and manage the OS-level daily renewal job status.

