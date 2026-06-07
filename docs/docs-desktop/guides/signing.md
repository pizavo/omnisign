---
sidebar_position: 1
---

import useBaseUrl from '@docusaurus/useBaseUrl';
import AppIcon from '@site/src/components/AppIcon';

# Signing a Document

This guide walks through the process of signing a PDF document using the OmniSign Desktop application.

## 1. Open a PDF

Click the **folder icon** <AppIcon name="folder" color="folder" label="Open file" /> in the toolbar to open a system file picker filtered to PDF files.
The document opens in the central viewer with page navigation and zoom controls.

## 2. Open the signing dialog

Click the **pen icon** <AppIcon name="sign" label="Sign" /> in the center of the toolbar to open the signing dialog.
The button is only enabled when a document is loaded.

OmniSign immediately begins discovering certificates from all available sources.
A loading spinner is shown while discovery is in progress.

<img src={useBaseUrl('/img/desktop/signing-discovery.avif')} alt="Opening the signing dialog and certificate discovery" />

## 3. Select a certificate

Once discovery completes, the dialog shows a certificate dropdown with all available
signing certificates. Certificates are gathered from:

- **PKCS#11 hardware tokens** — smart cards, USB tokens. Middleware libraries are
  auto-detected or manually registered in Settings → Tokens → PKCS#11 Libraries.
- **The operating-system certificate store** — the **Windows certificate store** and the
  **macOS Keychain** are read automatically where available.
- **PKCS#12 files** — click the **import button** (the upload icon <AppIcon name="upload" label="Import PKCS#12 file" /> beside the dropdown) to load
  a `.p12` / `.pfx` keystore. The imported certificates are added to the dropdown immediately.

Each dropdown entry is **source-aware**: it shows the certificate's common name, its
*valid until* date, and the **source** it came from — for example
`Jan Novák — valid until 14.03.2027; eToken 5110`. The source suffix is the hardware
token's label, the OS certificate store, or the loaded `.p12` file. When the same
identity is present on more than one token or store, the source is what tells the
entries apart, so you can pick the exact key you intend to sign with.

Entries also carry **eIDAS qualification** indicators: a rosette <AppIcon name="rosette" color="qualified" label="Qualified" /> marks a **Qualified** certificate,
a check-rosette <AppIcon name="rosette_check" color="qualifiedQscd" label="Qualified (QSCD)" /> marks a **Qualified (QSCD)** certificate whose key lives on a Qualified
Signature/Seal Creation Device, and a USB icon <AppIcon name="device_usb" color="success" label="PKCS#11 hardware token" /> marks a certificate held on a **PKCS#11 hardware
token**. The qualification of the selected certificate is also shown as a badge beneath the dropdown.

![Source-aware certificate dropdown](/img/desktop/signing-cert-dropdown.avif)

A PIN-protected token's certificates are listed **without asking for a PIN** wherever the token
exposes them as public objects (the case for Czech qualified tokens, for example). You can pick
such a certificate straight away — the PIN is requested later, when you [sign](#5-sign).

### Locked tokens

A token appears in a separate **Locked tokens** section only when it will **not** reveal its
certificates without a login. Each locked token has an **Unlock** button that opens a secure PIN
dialog; after unlocking, that token's certificates are added to the dropdown.

### Discovery warnings

If any token source encounters issues during discovery (e.g., a PKCS#11 library cannot be
loaded), a warning banner is shown at the top of the certificate section listing the
affected tokens and error details.

### When no certificates are found

If discovery turns up nothing, a banner above the dropdown spells out the next steps: insert a
smart card, import a PKCS#12 file, or add a PKCS#11 library path under Settings → Tokens → PKCS#11
Libraries. It also offers a **Show diagnostic info** button. The same PKCS#11 diagnostic dialog is
always available from the **info icon** <AppIcon name="alert_info" label="Diagnostic info" /> in the dialog header. See
[Troubleshooting → PKCS#11 tokens not detected](../troubleshooting.md#pkcs11-tokens-not-detected)
for what the dialog shows and how to fix the common cases.

### Rescanning for tokens

The dialog header has a **Rescan tokens** action. Use it when you have installed or
changed PKCS#11 middleware **while OmniSign is running** — no card or reader event would
otherwise trigger re-detection. The rescan is fire-and-forget: the control is replaced by
an inline progress indicator while it runs, and the certificate dropdown refreshes
automatically when it settles.

<img src={useBaseUrl('/img/desktop/signing-rescan.avif')} alt="Rescanning for tokens" />

The refresh is **silent** — a newly detected PIN-required token appears in the
**Locked tokens** section rather than opening a PIN dialog (unlocking stays an explicit
per-token action). When the rescan settles, a toast confirms the outcome:

- *Rescan complete — N PKCS#11 entries detected* — a brief confirmation.
- *Rescan complete — no PKCS#11 tokens detected* — shown with a **Show diagnostic info**
  action that opens the PKCS#11 diagnostic dialog, so you can troubleshoot why a token 
  plugged in is not being seen (commonly a smart-card middleware / ATR-mapping
  mismatch).

### Automatic refresh when a card or reader changes

While the signing dialog is open, inserting or removing a smart card — or plugging or
unplugging a reader — refreshes the certificate list automatically. A small inline
indicator is shown during the refresh, and your current selection is kept if that
certificate is still available afterward. These background refreshes are **silent** (no
toast and no PIN prompt), which is what distinguishes them from a manual **Rescan**.

:::note
Toasts such as the rescan confirmation appear at the bottom-right and persist across
dialogs: a toast raised inside the signing dialog keeps showing — and remains
actionable — even if you close the dialog before it disappears.
:::

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
grayed out and cannot be selected. See [Algorithms](../algorithms.md) for the full list
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

If you chose a certificate from a PIN-protected token that was listed without a PIN, OmniSign
prompts you for the token PIN at this point.

### Revocation warning

If the effective level is B-LT or B-LTA and revocation data (CRL/OCSP) cannot be fully
obtained, OmniSign shows an intermediate **revocation warning** screen listing the affected
certificates and warning details. You can:

- **Abort** — discard the signed output.
- **Continue** — accept the output despite missing revocation data. The signature may be at
  a lower effective level than requested.

![Signing revocation-warning screen](/img/desktop/signing-revocation-warning.avif)

## 6. Review the result

On success, the dialog shows:

- The **output file** path.
- The **signature ID** of the created signature.
- The achieved **PAdES level** (e.g., BASELINE-LTA).
- Any **warnings** produced during signing, categorized by severity.

![Signing success summary](/img/desktop/signing-success.avif)

Closing the dialog automatically reloads the signed document in the viewer so you can
inspect it immediately.

## 7. Renewal job offer

When signing at the B-LTA level, OmniSign may show a **renewal job offer** dialog after
the success screen. You can:

- **Assign to an existing job** — select a configured renewal job from the dropdown.
- **Create a new job** — define a new renewal job with a name, glob pattern, buffer days,
  and an optional profile.

![Renewal job offer dialog](/img/desktop/renewal-job-offer.avif)

If the output file is already covered by an existing job (detected from glob patterns), the
dialog shows the covering job name and no further action is needed.
