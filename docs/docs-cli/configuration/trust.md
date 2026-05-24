---
sidebar_position: 3.5
---

# Trusted Certificates

Directly trust individual CA or TSA certificates without building a full ETSI TS 119612
Trusted List XML. Trusted certificates are wired into the DSS validation engine alongside any
configured trusted lists and the EU LOTL.

Certificates are kept in the **app-managed trust store** (shared with the desktop app): each
certificate is imported once and stored under its SHA-256 **fingerprint**, which is its identity —
there is no separate name. Trust is partitioned into a **global** scope plus one scope per profile;
the resolved trust set for a profile is the union of the global scope and that profile's scope.

## `config trust add`

Import a PEM or DER certificate into the trust store.

| Option                 | Description                                                          |
|------------------------|---------------------------------------------------------------------|
| `-c, --cert <path>`    | **(Required)** Path to the PEM or DER certificate file              |
| `-t, --type <type>`    | Trust role: `ANY` (both CA and TSA), `CA`, or `TSA`. Default: `ANY` |
| `-p, --profile <name>` | Store in the given profile's scope instead of the global scope      |

On success the subject DN and fingerprint are printed. The original file is not needed afterwards.

## `config trust list`

List the trusted certificates in a scope — each shows subject, role, expiry, and fingerprint.

```bash
omnisign config trust list
omnisign config trust list --profile university
```

| Option                 | Description                                      |
|------------------------|--------------------------------------------------|
| `-p, --profile <name>` | List the given profile's scope instead of global |

## `config trust remove <fingerprint>`

Remove a trusted certificate from a scope by its fingerprint. A unique **prefix** is accepted, so
you can paste just the first few characters shown by `config trust list`.

```bash
omnisign config trust remove sha256-1a2b3c
omnisign config trust remove sha256-1a2b3c --profile university
```

| Option                 | Description                                             |
|------------------------|--------------------------------------------------------|
| `-p, --profile <name>` | Remove from the given profile's scope instead of global |

## Examples

```bash
# Trust a CA certificate globally (used for both signing and validation chains)
omnisign config trust add --cert /path/to/university-ca.pem

# Trust a TSA certificate for timestamp validation only
omnisign config trust add --cert /path/to/tsa.pem --type TSA

# Trust a certificate in a specific profile's scope
omnisign config trust add --cert dept-ca.pem --profile department

# List globally trusted certificates, then remove one by fingerprint prefix
omnisign config trust list
omnisign config trust remove sha256-9f8e7d
```

:::tip
Use `config trust add` for quick one-off trust anchors. For larger deployments with multiple TSPs
and services, build a proper [Trusted List](trusted-lists) via the `config tl build` workflow.
:::
