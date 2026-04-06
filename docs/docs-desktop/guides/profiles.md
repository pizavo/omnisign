---
sidebar_position: 4
---

# Profiles

Configuration profiles let you maintain separate sets of settings for different use cases —
for example, one profile for university thesis signing with a specific TSA and B-LTA level,
and another for internal document signing with different settings.

## Managing profiles

Open the **Profiles** panel from the right sidebar. From here you can:

- **Create** a new profile with custom settings
- **Edit** an existing profile's hash algorithm, signature level, TSA, and validation settings
- **Activate** a profile to make it the default for all operations
- **Deactivate** the current profile to revert to global defaults
- **Delete** a profile

## Profile settings

Each profile can override any combination of the following:

| Setting              | Description                                         |
|----------------------|-----------------------------------------------------|
| Description          | Human-readable purpose of the profile               |
| Hash algorithm       | Override the global default hash algorithm          |
| Encryption algorithm | Override the global default encryption algorithm    |
| Signature level      | Override the global default signature level         |
| Timestamp server     | Override the global TSA (URL, credentials, timeout) |
| Validation policy    | Override the global validation policy               |
| Disabled algorithms  | Additional algorithms to disable for this profile   |
| Trusted certificates | Profile-scoped CA/TSA certificates                  |
| Custom trusted lists | Profile-scoped ETSI Trusted List sources            |

Settings not specified in a profile inherit from the global configuration.

## Active profile indicator

When a profile is active, its name appears in the toolbar. All signing and validation
operations use the profile's settings merged with the global defaults.

:::tip
You can override the active profile for a single operation using the signing dialog's
profile selector, without changing the globally active profile.
:::

