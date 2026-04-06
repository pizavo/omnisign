---
sidebar_position: 1
---

# Signing a Document

This guide walks through the process of signing a PDF document using the OmniSign Desktop application.

## 1. Open a PDF

Click the **folder icon** in the toolbar or drag a PDF file into the application window.
The document opens in the central viewer with page navigation and zoom controls.

## 2. Open the signing dialog

Click the **pen icon** (✒️) in the toolbar to open the signing dialog.

## 3. Select a certificate

OmniSign automatically discovers certificates from all available sources:

- **OS-native stores** — Windows Certificate Store, macOS Keychain
- **PKCS#11 hardware tokens** — smart cards, USB tokens
- **PKCS#12 files** — click "Load from file" to import a `.p12` / `.pfx` keystore

If your hardware token requires a PIN, OmniSign will prompt you with a secure dialog.

Select the certificate you want to use from the dropdown list.

## 4. Configure signing options

- **Signature level** — choose from B-B, B-T, B-LT, or B-LTA
- **Hash algorithm** — SHA-256, SHA-384, or SHA-512 (more available in settings)
- **Reason / Location / Contact** — optional metadata embedded in the signature

:::tip
For long-lived documents, use **B-LTA** (Long-Term Archival) to ensure the signature
remains verifiable indefinitely when combined with periodic re-timestamping.
:::

## 5. Sign

Click **Sign** and choose the output file location. OmniSign signs the document and
shows a success confirmation with details about the created signature.

## 6. Renewal job offer

When signing at the B-LTA level, OmniSign may offer to add the signed file to a
**renewal job** for automatic re-timestamping. This ensures digital continuity
without manual intervention.

