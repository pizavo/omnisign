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
| `-d, --detailed`           | Show detailed output including DSS IDs, key usages, timestamp IDs, and resolved configuration                                        |
| `--report-out <path>`      | Write the raw DSS validation report to this file                                                                                     |
| `--report-format <format>` | Format of the report written by `--report-out` (`XML_DETAILED`, `XML_SIMPLE`, `XML_DIAGNOSTIC`, `XML_ETSI`). Default: `XML_DETAILED` |

All [config overrides](../configuration/config-overrides) are supported.

## Examples

```bash
omnisign validate -f contract.pdf
omnisign validate -f contract.pdf --detailed
omnisign validate -f contract.pdf --report-out report.xml --report-format XML_SIMPLE
omnisign validate -f contract.pdf --profile university --validation-policy CUSTOM -p policy.xml
```

## Sample output

```
═══════════════════════════════════════════════════════════════
                    VALIDATION REPORT
═══════════════════════════════════════════════════════════════
Document:      thesis-signed.pdf
Validated at:  2026-03-22T13:59:25.046040200Z
Overall:       ✅ VALID
═══════════════════════════════════════════════════════════════

⚠️ 1 trusted list could not be refreshed (eidas.gov.ie). Qualification assessment
   for certificates from these sources may be incomplete.

┌─ Signature 1 of 1
│
│  Indication:       ✅ PASSED
│  Signed by:        John Doe
│  Signature level:  PAdES-BASELINE-LTA
│  Signature time:   Sat Mar 21 20:43:08 CET 2026
│  Algorithms:       SHA512 / RSA
│
│  Certificate:
│    Subject:        CN=John Doe, O=Example University, C=CZ
│    Issuer:         CN=CA RSA 1, O=Example CA, C=GR
│    Serial:         73660465370300728244807694835464941913
│    Valid from:     Fri Sep 12 00:27:09 CEST 2025
│    Valid to:       Sun Sep 12 00:27:09 CEST 2027
│    Qualified:      No
│
│  ❌ Errors:
│     ℹ️ Unable to build a certificate chain up to a trusted list!
│
│  ⚠️ Warnings:
│     ℹ️ The signing certificate does not have an expected key-usage!
│
│  ℹ️ Information:
│     ℹ️ The certificate is not qualified.
│
└───────────────────────────────────────────────────────────────

┌─ Timestamps (2)
│
│  1. Signature timestamp
│     Indication:    ✅ PASSED
│     Produced:      Sat Mar 21 20:43:08 CET 2026
│     TSA:           CN=tsa.example.com, O=TSA Provider
│
│  2. Document timestamp
│     Indication:    ✅ PASSED
│     Produced:      Sat Mar 21 20:43:09 CET 2026
│     TSA:           CN=tsa.example.com, O=TSA Provider
│
└───────────────────────────────────────────────────────────────

═══════════════════════════════════════════════════════════════
Summary: 1 passed (1 total)
═══════════════════════════════════════════════════════════════
```

## Understanding the output

### Errors, warnings, and information

Each signature and timestamp may contain `❌ Errors`, `⚠️ Warnings`, and `ℹ️ Information` sections.
These are individual constraint check results reported by the DSS validation engine
(ETSI EN 319 102-1) and do not necessarily indicate a problem with the signature itself:

- **Errors** — Constraint checks that failed according to the active validation policy.
  For example, *"Unable to build a certificate chain up to a trusted list!"* means the
  signing certificate is not issued by any CA present in the EU LOTL or a registered
  custom trusted list. This is expected for certificates outside the eIDAS trust framework.
  The overall `✅ PASSED` indication remains the authoritative result.
- **Warnings** — Non-critical findings. For example, *"The signing certificate does not
  have an expected key-usage!"* is reported when the certificate lacks a `nonRepudiation`
  key usage.
- **Information** — Purely informational notes. Additional details are available with `--detailed`.

### Trusted list notices

If one or more EU member-state trusted lists cannot be reached at validation time (e.g., due to
a network error), a notice is printed below the report header. This does not affect the
cryptographic validity of the signature. Only the eIDAS qualification assessment for signing
certificates issued by the affected member state may be unavailable.

### INDETERMINATE timestamps in valid LTA signatures

In a freshly created PAdES-BASELINE-LTA document it is normal for both timestamps to show
`INDETERMINATE`. DSS validates each timestamp token in isolation (ETSI EN 319 102-1) before
aggregating results into the overall indication. The `✅ VALID` overall indication is the
authoritative result. Renew the archive timestamp periodically to maintain long-term
cryptographic provability.

## JSON output

Pass `--json` (the global flag) to get machine-readable output:

```bash
omnisign --json validate -f contract.pdf
```

Returns a JSON object with `success`, `documentName`, `overallResult`, `signatures`,
`timestamps`, and optional `error` fields. Useful for automated validation pipelines.

