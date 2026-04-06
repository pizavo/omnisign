---
sidebar_position: 2
---

# Validating Signatures

This guide explains how to validate electronically signed PDF documents using OmniSign Desktop.

## 1. Open a signed PDF

Open a signed PDF file using the toolbar folder icon or by dragging it into the window.

## 2. Open the validation panel

Click the **signature icon** in the left sidebar to open the Signatures panel.
When the panel first opens, it shows a prompt to use the **refresh** button (↻) in the
panel header to retrieve and validate signature information. Click the refresh button to
start the validation process.

You can click refresh again at any time to re-validate the document (e.g., after changing
trust settings or adding trusted certificates).

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

Click the **download icon** (⬇) in the panel header to open the export format menu.
Each entry shows the format name, a description, and the file extension. Formats that
require raw DSS report data are greyed out when the data is not available.

| Format                       | Extension | Description                                                                        |
|------------------------------|-----------|------------------------------------------------------------------------------------|
| Plain Text                   | `.txt`    | Human-readable summary with signature details, timestamps, and warnings.           |
| JSON                         | `.json`   | Machine-readable JSON with signatures, certificates, timestamps, and a summary.    |
| XML — Detailed Report        | `.xml`    | ETSI EN 319 102-1 detailed report with per-check building-block results.           |
| XML — Simple Report          | `.xml`    | DSS simple report — concise per-signature summary in XML.                          |
| XML — Diagnostic Data        | `.xml`    | Full low-level cryptographic evidence (certificates, revocation data, timestamps). |
| XML — ETSI Validation Report | `.xml`    | ETSI TS 119 102-2 SVR — standardised interoperable validation report.              |

After selecting a format, a save dialog lets you choose the output location.

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
  EU LOTL or any registered custom trusted list. Register your organization's CA using
  Settings → Validation → Trusted Lists.
- *"The signing certificate does not have an expected key-usage!"* — the certificate lacks
  `nonRepudiation`. Common for S/MIME certificates.

