---
sidebar_position: 2
---

# Validating Signatures

This guide explains how to validate electronically signed PDF documents using OmniSign Desktop.

## 1. Open a signed PDF

Open a signed PDF file using the toolbar folder icon or by dragging it into the window.

## 2. Open the validation panel

Click the **shield icon** (🛡️) in the left sidebar to open the Signatures panel.
Click **Validate** to start the validation process.

## 3. Read the results

The validation panel displays:

- **Overall indication** — `VALID`, `INVALID`, or `INDETERMINATE`
- **Signature details** — for each signature in the document:
  - Signer identity (subject DN)
  - Signature level (B-B, B-T, B-LT, B-LTA)
  - Signing time
  - Hash and encryption algorithms used
  - Certificate details (issuer, validity, serial number, qualification)
  - Timestamp details (production time, TSA identity)
- **Errors, warnings, and information** — constraint check results from the ETSI validation engine

## 4. Export a validation report

Click the **export** button to save the full DSS validation report as XML.
Available formats:

| Format             | Description                                     |
|--------------------|-------------------------------------------------|
| `XML_DETAILED`     | Complete validation report with all diagnostics |
| `XML_SIMPLE`       | Simplified report with key results              |
| `XML_DIAGNOSTIC`   | Low-level diagnostic data                       |
| `XML_ETSI`         | ETSI-standard validation report                 |

## Understanding results

### VALID vs. PASSED

- **VALID** — the overall document validation result; aggregates all signatures
- **PASSED** — individual signature or timestamp token passed its constraint checks

### Trust levels

- **Qualified** — the certificate is issued by a CA in the EU LOTL and meets eIDAS requirements
- **Not Qualified** — the certificate is not in the EU trust framework (common for institutional certificates)

:::info
A signature can be cryptographically valid (PASSED) even when the certificate is not qualified.
Qualification relates to eIDAS legal standing, not cryptographic strength.
:::

### Common warnings

- *"Unable to build a certificate chain up to a trusted list!"* — the signing CA is not in the
  EU LOTL or any registered custom trusted list. Register your organisation's CA using
  Settings → Validation → Trusted Lists.
- *"The signing certificate does not have an expected key-usage!"* — the certificate lacks
  `nonRepudiation`. Common for S/MIME certificates.

