---
sidebar_position: 3.5
---

# Trusted Certificates

Directly trust individual CA or TSA certificates without building a full ETSI TS 119612
Trusted List XML. Certificates registered here are wired into the DSS validation engine
alongside any configured trusted lists and the EU LOTL.

The certificate file is read at registration time; its DER-encoded bytes and subject DN
are stored inline in the config. The original file is no longer needed after registration.

## `config trust add`

| Option                 | Description                                                               |
|------------------------|---------------------------------------------------------------------------|
| `-n, --name <name>`    | **(Required)** Unique label for this trusted certificate                  |
| `-c, --cert <path>`    | **(Required)** Path to the PEM or DER certificate file                    |
| `-t, --type <type>`    | Certificate type: `ANY` (both CA and TSA), `CA`, or `TSA`. Default: `ANY` |
| `-p, --profile <name>` | Store in the given profile instead of the global config                   |

## `config trust list`

List all directly trusted certificates.

```bash
omnisign config trust list
omnisign config trust list --profile university
```

| Option                 | Description                                                |
|------------------------|------------------------------------------------------------|
| `-p, --profile <name>` | List certificates from the given profile instead of global |

## `config trust remove <name>`

Remove a directly trusted certificate.

```bash
omnisign config trust remove my-ca
omnisign config trust remove my-ca --profile university
```

| Option                 | Description                                                |
|------------------------|------------------------------------------------------------|
| `-p, --profile <name>` | Remove from the given profile instead of the global config |

## Examples

```bash
# Trust a CA certificate globally (used for both signing and validation)
omnisign config trust add --name "University CA" --cert /path/to/university-ca.pem

# Trust a TSA certificate for timestamp validation only
omnisign config trust add --name "Internal TSA" --cert /path/to/tsa.pem --type TSA

# Trust a certificate in a specific profile
omnisign config trust add --name "Dept CA" --cert dept-ca.pem --profile department

# List all globally trusted certificates
omnisign config trust list

# Remove a trusted certificate
omnisign config trust remove "University CA"
```

:::tip
Use `config trust add` for quick one-off trust anchors. For larger deployments with
multiple TSPs and services, consider building a proper [Trusted List](trusted-lists)
via the `config tl build` workflow.
:::

