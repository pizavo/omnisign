---
sidebar_position: 7
---

# Trusted Certificates Panel

The Trusted Certificates panel provides a read-only overview of all CA and TSA certificates
that are currently trusted by OmniSign. It combines certificates from both the active
profile and the global configuration into a single view.

## Opening the panel

Click the **certificate icon** in the right sidebar to toggle the Trusted Certificates panel.

## Sections

The panel is divided into two sections, separated by labeled headers:

### Profile certificates

Shown only when a profile is active. Displays certificates scoped to the current profile,
with the profile name displayed as a chip badge. These certificates are configured in the
profile editor under the Trusted Certificates section.

### Global certificates

Certificates registered in the global configuration via Settings → Validation → Trusted
Certificates. These are always shown regardless of whether a profile is active.

## Certificate details

Each certificate entry shows:

- **Subject DN** — the distinguished name of the certificate subject.
- **Issuer** — the issuing certificate authority.
- **Serial number** — the certificate's serial number.
- **Usage** — whether the certificate is trusted as a CA, TSA, or both.

## Empty state

When no trusted certificates are configured, the panel shows a prompt directing you to
**Settings → Validation → Trusted Certificates** to add them.

:::tip
To quickly check which CAs are trusted for a signing or validation operation, open this
panel before running the operation. Certificates from both the profile and global scopes
are merged during config resolution.
:::

