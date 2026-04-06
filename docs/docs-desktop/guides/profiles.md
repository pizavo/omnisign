---
sidebar_position: 4
---

# Profiles

Configuration profiles let you maintain separate sets of settings for different use cases —
for example, one profile for university thesis signing with a specific TSA and B-LTA level,
and another for internal document signing with different settings.

## Managing profiles

Open the **Profiles** panel from the right sidebar (user icon). From here you can:

- **Create** a new profile with custom settings.
- **Edit** an existing profile — opens a dedicated edit form in the same panel.
- **Activate** a profile to make it the default for all operations.
- **Deactivate** the current profile to revert to global defaults.
- **Delete** a profile.

## Profile settings

Each profile can override any combination of the following settings. Fields not specified
in a profile inherit from the global configuration.

### Signing overrides

| Setting              | Description                                                                                             |
|----------------------|---------------------------------------------------------------------------------------------------------|
| Description          | Human-readable purpose of the profile.                                                                  |
| Hash algorithm       | Override the global default [hash algorithm](../algorithms.md#hash-algorithms), or inherit.             |
| Encryption algorithm | Override the global default [encryption algorithm](../algorithms.md#encryption-algorithms), or inherit. |

### Signature level (tri-state toggles)

The signature level is controlled by two **tri-state toggles** instead of a dropdown.
Each toggle has three positions:

| Position     | Meaning                                                              |
|--------------|----------------------------------------------------------------------|
| **Inherit**  | Use the global default setting (no profile override).                |
| **Enabled**  | Force this timestamp type on for all operations using this profile.  |
| **Disabled** | Force this timestamp type off for all operations using this profile. |

The two toggles are:

- **Signature timestamp & revocation data** (B-LT).
- **Archival document timestamp** (B-LTA) — enabling this automatically implies the
  signature timestamp.

The effective signature level is derived from the combination:

| Signature TS | Archival TS |   Result   |
|:------------:|:-----------:|:----------:|
|   Disabled   |  Disabled   |    B-B     |
|   Enabled    |  Disabled   |    B-LT    |
|   Enabled    |   Enabled   |   B-LTA    |
|   Inherit    |   Inherit   | *(global)* |

### Timestamp server

Override the global TSA for operations using this profile:

| Setting  | Description                                                        |
|----------|--------------------------------------------------------------------|
| TSA URL  | HTTPS endpoint of the timestamp authority.                         |
| Username | HTTP Basic authentication username (if required).                  |
| Password | HTTP Basic authentication password.                                |
| Timeout  | Request timeout in milliseconds (default: 30 000).                 |

:::note
The TSA password is stored in the operating system's native credential store — never in
the configuration file. A stored-password indicator appears when a credential already exists.
:::

### Disabled algorithms

Disable specific hash or encryption algorithms for this profile. Disabled algorithms
cannot be selected when this profile is active, even if they are allowed globally.

### Trusted certificates

Add CA and TSA certificates scoped to this profile. These are merged with the global
trusted certificates during config resolution.

### Custom trusted lists

Register profile-scoped ETSI Trusted List XML sources. A **Build** button opens the
[Trusted List builder](tl-builder.md) dialog. When the builder compiles a list with
"Register after compile" enabled, the resulting entry is automatically added to this
profile's trusted lists section.

## Active profile indicator

When a profile is active, its name appears in the toolbar. All signing and validation
operations use the profile's settings merged with the global defaults (three-layer merge:
global → profile → operation overrides).

:::tip
You can override the active profile for a single operation using the signing dialog's
profile selector, without changing the globally active profile.
:::

