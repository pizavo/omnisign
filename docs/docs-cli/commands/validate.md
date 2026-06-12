---
sidebar_position: 1
---

# validate

Validate a signed PDF document against the configured ETSI validation policy.

```
omnisign validate -f <file> [options]
```

## Options

| Option                     | Description                                                                                                                          |
|----------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `-f, --file <path>`        | **(Required)** Path to the PDF file to validate                                                                                      |
| `-p, --policy <path>`      | Path to a custom ETSI validation policy XML file                                                                                     |
| `--profile <name>`         | Use a named configuration profile for this operation                                                                                 |
| `-d, --detailed`           | Expand every certificate in a chain into its full parsed dump (all fields and extensions), plus the raw DSS signature/timestamp IDs and the resolved configuration                                        |
| `--report-out <path>`      | Write the raw DSS validation report to this file                                                                                     |
| `--report-format <format>` | Format of the report written by `--report-out` (`XML_DETAILED`, `XML_SIMPLE`, `XML_DIAGNOSTIC`, `XML_ETSI`). Default: `XML_DETAILED` |

All [config overrides](../configuration/config-overrides) are supported.

## Examples

```bash
omnisign validate -f contract.pdf
omnisign validate -f contract.pdf --detailed
omnisign validate -f contract.pdf --report-out report.xml --report-format XML_SIMPLE
omnisign validate -f contract.pdf --profile university --validation-policy CUSTOM_FILE -p policy.xml
```

## Sample output

```
OmniSign — Validation Report
════════════════════════════════════════
Document:        thesis-signed.pdf
Validation time: 2026-03-22T13:59:25Z
Overall result:  VALID

── Signature 1 of 1 ──
  Indication:     TOTAL_PASSED
  Signed by:      John Doe
  Level:          PAdES-BASELINE-LTA
  Time:           2026-03-21T20:43:08Z
  Hash algorithm: SHA512
  Encryption:     RSA

  Certificate:
    Subject:      CN=John Doe, O=Example University, C=CZ
    Issuer:       CN=CA RSA 1, O=Example CA, C=GR
    Serial:       73660465370300728244807694835464941913
    Valid from:   2025-09-12
    Valid to:     2027-09-12
    Public key:   RSA
    SHA-256:      9F:86:D0:81:88:4C:7D:65
  Certificate chain:
    Certificate Authority: CA RSA 1
    Signing certificate: John Doe
  Errors:
    • Unable to build a certificate chain up to a trusted list!
  Warnings:
    • The signing certificate does not have an expected key-usage!
  Information:
    • The certificate is not qualified.

── Document Timestamps ──
  1. Document timestamp
    Indication:      TOTAL_PASSED
    Production time: 2026-03-21T20:43:09Z
    TSA:             CN=tsa.example.com, O=TSA Provider
    EU LOTL:         Yes
    Certificate chain:
      Root CA: TSA Root CA [trusted via EU LOTL]
      Timestamp certificate: tsa.example.com

── Trusted List Warnings ──
  ⚠ 1 trusted list could not be refreshed (eidas.gov.ie). Qualification assessment for
    certificates from these sources may be incomplete.
```

## Understanding the output

### Errors, warnings, and information

Each signature and timestamp may contain `Errors`, `Warnings`, and `Information` sections.
These are individual constraint check results reported by the DSS validation engine
(ETSI EN 319 102-1) and do not necessarily indicate a problem with the signature itself:

- **Errors** — Constraint checks that failed according to the active validation policy.
  For example, *"Unable to build a certificate chain up to a trusted list!"* means the
  signing certificate is not issued by any CA present in the EU LOTL or a registered
  custom trusted list. This is expected for certificates outside the eIDAS trust framework.
  The overall `PASSED` indication remains the authoritative result.
- **Warnings** — Non-critical findings. For example, *"The signing certificate does not
  have an expected key-usage!"* is reported when the certificate lacks a `nonRepudiation`
  key usage.
- **Information** — Purely informational notes. Additional details are available with `--detailed`.

### Certificate chain

Under each signature's `Certificate:` block — and each timestamp — the `Certificate chain:` section
lists the path the validator built, top-down: the trust anchor first, each intermediate CA below it,
and the end-entity (the signing or timestamp certificate) last. A `[trusted via …]` marker names
where a certificate is trusted — a trusted list such as the EU LOTL, the global trust store, or a
profile's store. In the example above the timestamp's anchor is reached via the EU LOTL, while the
signing certificate's chain reaches no trusted list (hence the error). Pass `--detailed` to expand
every certificate into its complete parsed dump — every distinguished-name component and extension —
the terminal equivalent of the desktop app's full-certificate view.

### Trusted list notices

If one or more EU member-state trusted lists cannot be reached at validation time (e.g., due to
a network error), a notice is printed below the report header. This does not affect the
cryptographic validity of the signature. Only the eIDAS qualification assessment for signing
certificates issued by the affected member state may be unavailable.

### EU LOTL membership

A signature or timestamp whose trust anchor is on the **EU List of Trusted Lists** (the LOTL, or a
national list that is a member of it) prints an `EU LOTL: Yes` line. The line is omitted when the
trust anchor comes from a custom trusted list or is unknown. That is why the example signature
above — whose chain does not reach a trusted list — has no such line, while its timestamps do (a
common case: a non-eIDAS signing certificate combined with a qualified TSA). This reflects only trust-anchor
membership and is independent of the qualification tier and the overall result.

### INDETERMINATE timestamps in valid LTA signatures

In a freshly created PAdES-BASELINE-LTA document it is normal for both timestamps to show
`INDETERMINATE`. DSS validates each timestamp token in isolation (ETSI EN 319 102-1) before
aggregating results into the overall indication. The `VALID` overall indication is the
authoritative result. Renew the archive timestamp periodically to maintain long-term
cryptographic provability.

## JSON output

Pass `--json` (the global flag) to get machine-readable output:

```bash
omnisign --json validate -f contract.pdf
```

The result is a thin envelope — `success`, an optional `error`, an optional `rawReportPath`, and the
validation `report` (null when the operation could not run):

```json
{
  "success": true,
  "report": {
    "documentName": "contract.pdf",
    "overallResult": "VALID",
    "signatures": [ "…" ],
    "timestamps": [ "…" ],
    "summary": { "total": 1, "passed": 1, "failed": 0, "indeterminate": 0 },
    "tlWarnings": []
  }
}
```

The `report` is the same shape the desktop and web clients produce, so a pipeline can consume any of
them identically. Each signature carries its signing-certificate `chain` — every entry with its
parsed `details` and `trustedVia` trust sources — and its `revocations`; each signature and timestamp
carries `euLotlBacked` (mirroring the `EU LOTL` line) and its own certificate `chain`. When the
operation fails, `report` is null and `error` holds `message`, `details`, and `cause`:

```json
{ "success": false, "error": { "message": "Document is not a valid PDF" } }
```

