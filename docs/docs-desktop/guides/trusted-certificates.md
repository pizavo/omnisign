---
sidebar_position: 7
---

import AppIcon from '@site/src/components/AppIcon';

# Trusted Certificates Panel

The Trusted Certificates panel provides a read-only overview of all CA and TSA certificates
that are currently trusted by OmniSign. It combines certificates from both the active
profile and the global configuration into a single view.

## Opening the panel

Click the **certificate icon** <AppIcon name="certificate" label="Trusted Certificates panel" /> in the right sidebar to toggle the Trusted Certificates panel.

![Trusted Certificates panel](/img/desktop/trusted-certs-panel.avif)

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
- **Trust role** — a badge showing whether the certificate is trusted as **CA**, **TSA**, or
  **Any** (both).
- **Expiry** — the certificate's *not-after* date.
- **Fingerprint** — a shortened SHA-256 fingerprint (e.g. `sha256-1a2b3c…7d8e9f`).

## Adding a certificate from a validation report

You can trust a certificate straight from a validation report, without opening Settings or a profile
editor. In the Signatures panel, open a signature's or timestamp's
[full certificate view](validation.md#the-full-certificate-view), select a certificate, and click the
**shield icon** <AppIcon name="shield_plus" label="Add to trusted certificates" />. With no profile
active it goes to the **global** store; with a profile active, you choose **Global** or the active
profile. The trust role is taken from context — **CA** from a signature's chain, **TSA** from a
timestamp's. Unlike the profile editor and Settings, this commits immediately (there is no staging).

## Empty state

When no trusted certificates are configured, the panel shows a prompt directing you to
**Settings → Validation → Trusted Certificates** to add them.

:::tip
To quickly check which CAs are trusted for a signing or validation operation, open this
panel before running the operation. Certificates from both the profile and global scopes
are merged during config resolution.
:::

