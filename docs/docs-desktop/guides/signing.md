---
sidebar_position: 1
---

# Signing a Document

This guide walks through the process of signing a PDF document using the OmniSign Desktop application.

## 1. Open a PDF

Click the **folder icon** in the toolbar to open a system file picker filtered to PDF files.
The document opens in the central viewer with page navigation and zoom controls.

## 2. Open the signing dialog

Click the **pen icon** (✒️) in the center of the toolbar to open the signing dialog.
The button is only enabled when a document is loaded.

OmniSign immediately begins discovering certificates from all available sources.
A loading spinner is shown while discovery is in progress.

## 3. Select a certificate

Once discovery completes, the dialog shows a certificate dropdown with all available
signing certificates. Certificates are gathered from:

- **PKCS#11 hardware tokens** — smart cards, USB tokens. Middleware libraries are
  auto-detected or manually registered in Settings → Tokens → PKCS#11 Libraries.
- **PKCS#12 files** — click the **Load from file** button to import a `.p12` / `.pfx`
  keystore. The imported certificates are added to the dropdown immediately.

### Locked tokens

Some hardware tokens require a PIN before their certificates can be listed. These appear
in a separate **Locked tokens** section with an **Unlock** button next to each. Clicking
Unlock opens a secure PIN dialog. After unlocking, the token's certificates are added to
the dropdown.

### Discovery warnings

If any token source encounters issues during discovery (e.g., a PKCS#11 library cannot be
loaded), a warning banner is shown at the top of the certificate section listing the
affected tokens and error details.

## 4. Configure signing options

### Signature level

The signature level is controlled by two checkboxes rather than a dropdown:

| Checkbox                                                                       | Effective level |
|--------------------------------------------------------------------------------|-----------------|
| Neither checked                                                                | **B-B** (basic) |
| ☑ Include signature timestamp & revocation data                                | **B-LT**        |
| ☑ Include signature timestamp & revocation data + ☑ Include archival timestamp | **B-LTA**       |

Checking the archival timestamp automatically enables the signature timestamp.

:::note
The signing dialog does not offer **B-T** as a standalone option. Enabling the signature
timestamp always targets **B-LT** (timestamp plus embedded revocation data). B-T can only
be reached as a fallback when B-LT is requested, but revocation data cannot be obtained —
in that case the [timestamping dialog](timestamping.md) offers a Signature Timestamp
extension as a separate operation.
:::

### Hash algorithm

Select the digest algorithm from the dropdown. The default is inherited from the active
profile or global settings. Algorithms that are disabled in the configuration appear
greyed out and cannot be selected. See [Algorithms](../algorithms.md) for the full list
of supported hash algorithms and their compatibility with encryption algorithms.

### Metadata

Optional fields embedded in the PDF signature dictionary:

- **Reason** — purpose of the signature.
- **Location** — geographic location of signing.
- **Contact info** — contact details of the signer.

### Output file

The **Output file** field is pre-filled with a suggested path. Edit it to write elsewhere.

### Add to renewal job

When the effective level is B-LTA, an **Add to renewal job** checkbox appears. If the output
path is already covered by an existing renewal job's glob patterns, the checkbox is forced
on and disabled — the file will be renewed automatically regardless.

:::tip
For long-lived documents, use **B-LTA** (Long-Term Archival) to ensure the signature
remains verifiable indefinitely when combined with periodic re-timestamping.
:::

## 5. Sign

Click **Sign** to start the signing operation. A progress indicator is shown while signing
is in progress; the dialog cannot be dismissed during this phase.

### Revocation warning

If the effective level is B-LT or B-LTA and revocation data (CRL/OCSP) cannot be fully
obtained, OmniSign shows an intermediate **revocation warning** screen listing the affected
certificates and warning details. You can:

- **Abort** — discard the signed output.
- **Continue** — accept the output despite missing revocation data. The signature may be at
  a lower effective level than requested.

## 6. Review the result

On success, the dialog shows:

- The **output file** path.
- The **signature ID** of the created signature.
- The achieved **PAdES level** (e.g., BASELINE-LTA).
- Any **warnings** produced during signing, categorized by severity.

Closing the dialog automatically reloads the signed document in the viewer so you can
inspect it immediately.

## 7. Renewal job offer

When signing at the B-LTA level, OmniSign may show a **renewal job offer** dialog after
the success screen. You can:

- **Assign to an existing job** — select a configured renewal job from the dropdown.
- **Create a new job** — define a new renewal job with a name, glob pattern, buffer days,
  and an optional profile.

If the output file is already covered by an existing job (detected from glob patterns), the
dialog shows the covering job name and no further action is needed.
