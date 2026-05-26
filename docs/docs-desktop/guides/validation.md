---
sidebar_position: 2
---

import useBaseUrl from '@docusaurus/useBaseUrl';

# Validating Signatures

This guide explains how to validate electronically signed PDF documents using OmniSign Desktop.

## 1. Open a signed PDF

Open a signed PDF file using the toolbar folder icon, which opens a system file picker
filtered to PDF files.

## 2. Open the validation panel

Click the **signature icon** in the left sidebar to open the Signatures panel.
When the panel first opens, it shows a prompt to use the **refresh** button (↻) in the
panel header to retrieve and validate signature information. Click the refresh button to
start the validation process.

You can click refresh again at any time to re-validate the document (e.g., after changing
trust settings or adding trusted certificates).

<img src={useBaseUrl('/img/desktop/validation-refresh.avif')} alt="Refreshing the Signatures panel to validate the document" />

## 3. Read the results

The panel opens with an **overall result badge** — `VALID`, `INVALID`, or `INDETERMINATE`
(or `NO SIGNATURES` when the document carries none) — with a [trust-tier](#trust-levels) rosette
beside it when the signatures are qualified. Below the badge are the **document name** and
**validation time**, followed by collapsible sections.

### Signatures

A **Signatures (N)** group lists every signature; its shield icon reflects the aggregate result.
Expand a signature to see:

- **Indication** / **Sub-indication** — `PASSED`, `FAILED`, or `INDETERMINATE`, plus the ETSI
  sub-indication when present.
- **Signed by**, **Level** (e.g. BASELINE-LTA), and signing **Time**.
- **Qualification** and **Trust** tier (with a rosette for qualified signatures).
- **Hash algorithm** and **Encryption** algorithm.
- **Errors**, **Warnings**, **Qualification Errors**, and **Qualification Warnings** — shown when
  the validation produced any.

Each signature has a nested **Certificate** section (subject, issuer, serial number, validity
window, key usages, public-key algorithm, and SHA-256 fingerprint) and, when the signature carries
a signature timestamp, a nested **Signature timestamp** section (production time, qualification, and
TSA).

![Expanded signature accordion](/img/desktop/validation-signature-accordion.avif)

### Document timestamps

When the document contains document-level timestamps — for example, the archival timestamps in a
B-LTA file — a **Document Timestamps (N)** group lists each one with its indication, production
time, qualification, TSA, and any errors or warnings.

### Trusted list warnings

If validation surfaced any trusted-list issues, a **Trusted List Warnings** section appears at the
bottom of the report.

## 4. Export a validation report

Click the **download icon** (⬇) in the panel header to open the export format menu.
Each entry shows the format name, a description, and the file extension. Formats that
require raw DSS report data are greyed out when the data is not available.

![Validation report export menu](/img/desktop/validation-export-menu.avif)

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

Each signature's trust tier is shown with a rosette icon, and an overall rosette appears next to
the result badge:

- **Qualified (QSCD)** — a qualified certificate whose private key is held on a Qualified
  Signature/Seal Creation Device — the strongest eIDAS tier.
- **Qualified** — the certificate meets eIDAS qualified requirements (issued under the EU trust
  framework), but QSCD status was not confirmed.
- **Not Qualified** — the certificate is not in the EU trust framework (common for institutional
  certificates); no rosette is shown.

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

