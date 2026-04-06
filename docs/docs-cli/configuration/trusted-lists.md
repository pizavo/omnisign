---
sidebar_position: 3
---

# Trusted Lists

Register custom ETSI Trusted List sources for signature validation. These are used
alongside (or instead of) the EU LOTL when validating signatures from CAs that are
not part of the eIDAS trust framework.

## `config tl add`

Register a custom trusted list source.

| Option                    | Description                                                                 |
|---------------------------|-----------------------------------------------------------------------------|
| `-n, --name <name>`       | **(Required)** Unique label for this trusted list                           |
| `-s, --source <url/path>` | **(Required)** HTTPS URL or file path to the TL XML                         |
| `--signing-cert <path>`   | PEM/DER certificate to verify the TL's XML signature (strongly recommended) |
| `-p, --profile <name>`    | Store in the given profile instead of the global config                     |

## `config tl list`

List all registered custom trusted lists.

```bash
omnisign config tl list
omnisign config tl list --profile university
```

## `config tl remove <name>`

Remove a registered custom trusted list.

```bash
omnisign config tl remove my-org-tl
```

## `config tl build` — Build custom trusted lists

The TL builder lets you create your own ETSI TS 119612-compliant Trusted List XML,
useful for organisations that need to trust internal CAs.

| Subcommand                                                         | Description                                                |
|--------------------------------------------------------------------|------------------------------------------------------------|
| `config tl build create <name>`                                    | Guided interactive wizard to build a complete trusted list |
| `config tl build list`                                             | List all stored TL builder drafts                          |
| `config tl build show <name>`                                      | Show the contents of an existing draft                     |
| `config tl build tsp add <draft> --name <tsp-name>`                | Add a Trust Service Provider to a draft                    |
| `config tl build tsp remove <draft> <tsp-name>`                    | Remove a TSP from a draft                                  |
| `config tl build service add <draft> <tsp-name> --name <svc> ...`  | Add a trust service to a TSP in a draft                    |
| `config tl build service remove <draft> <tsp-name> <service-name>` | Remove a service from a TSP                                |
| `config tl build compile <draft>`                                  | Compile a draft to a TL XML and optionally register it     |
| `config tl build delete <draft>`                                   | Delete a draft                                             |

### `service add` options

| Option              | Description                                                                                          |
|---------------------|------------------------------------------------------------------------------------------------------|
| `--name, -n <name>` | **(Required)** Human-readable name of the service                                                    |
| `--type-id <uri>`   | **(Required)** Service type identifier URI (e.g. `http://uri.etsi.org/TrstSvc/Svctype/CA/QC`)        |
| `--status <uri>`    | **(Required)** Service status URI (e.g. `http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted`) |
| `--cert <path>`     | **(Required)** Path to the PEM or DER certificate representing the service                           |

### `tsp add` options

| Option                | Description                                                |
|-----------------------|------------------------------------------------------------|
| `--name, -n <name>`   | **(Required)** Official name of the Trust Service Provider |
| `--trade-name <name>` | Optional trade/brand name                                  |
| `--info-url <url>`    | URL pointing to the TSP's information page or registration |

### `compile` options

| Option                 | Description                                                                      |
|------------------------|----------------------------------------------------------------------------------|
| `-o, --out <path>`     | Output path for the XML file (default: `<draft-name>.xml` in current directory)  |
| `--register`           | After compiling, automatically register the output file as a custom TL source    |
| `-p, --profile <name>` | When `--register` is used, store the TL in this profile instead of global config |

### Example workflow

```bash
# Create a draft interactively
omnisign config tl build create my-org

# Or build non-interactively: add a TSP
omnisign config tl build tsp add my-org --name "Example University CA"

# Add a service to the TSP
omnisign config tl build service add my-org "Example University CA" \
  --name "CA/QC Service" \
  --type-id "http://uri.etsi.org/TrstSvc/Svctype/CA/QC" \
  --status "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted" \
  --cert /path/to/ca-cert.pem

# Compile the draft and register it
omnisign config tl build compile my-org --register

# List all drafts
omnisign config tl build list

# Verify it's registered
omnisign config tl list
```

