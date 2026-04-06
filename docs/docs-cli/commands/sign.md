---
sidebar_position: 2
---

# sign

Sign a PDF document with a PAdES digital signature.

```
omnisign sign -f <input> -o <output> [options]
```

## Options

| Option                      | Description                                                         |
|-----------------------------|---------------------------------------------------------------------|
| `-f, --file <path>`         | **(Required)** Path to the input PDF                                |
| `-o, --output <path>`       | **(Required)** Path for the signed output PDF                       |
| `-c, --certificate <alias>` | Certificate alias to use (see `certificates list`)                  |
| `-r, --reason <text>`       | Reason for signing (embedded in the signature)                      |
| `--location <text>`         | Location of signing (embedded in the signature)                     |
| `--contact <text>`          | Contact information of the signer (embedded in the signature)       |
| `--no-timestamp`            | Omit the RFC 3161 timestamp — produces B-B instead of B-T or higher |
| `--profile <name>`          | Use a named configuration profile for this operation                |
| `--visible`                 | Add a visible signature appearance                                  |
| `--vis-page <n>`            | Page for the visible signature (default: `1`)                       |
| `--vis-x <n>`               | X position in PDF user units (required with `--visible`)            |
| `--vis-y <n>`               | Y position in PDF user units (required with `--visible`)            |
| `--vis-width <n>`           | Width in PDF user units (required with `--visible`)                 |
| `--vis-height <n>`          | Height in PDF user units (required with `--visible`)                |
| `--vis-text <text>`         | Custom text inside the visible signature                            |
| `--vis-image <path>`        | Path to an image for the visible signature                          |

All [config overrides](../configuration/config-overrides) are supported.

## Examples

```bash
# Sign at the default level (B-T) using the first available certificate
omnisign sign -f thesis.pdf -o thesis-signed.pdf

# Sign at B-LTA level with a specific certificate and a visible signature
omnisign sign -f thesis.pdf -o thesis-signed.pdf \
  -c "My Qualified Certificate" \
  --signature-level PADES_BASELINE_LTA \
  --reason "Author signature" \
  --visible --vis-x 50 --vis-y 700 --vis-width 200 --vis-height 50

# Sign without a timestamp (B-B only)
omnisign sign -f doc.pdf -o doc-signed.pdf --no-timestamp
```

## Signature levels

| Level                  | Description                                                        |
|------------------------|--------------------------------------------------------------------|
| `PADES_BASELINE_B`     | Basic electronic signature — no timestamp                          |
| `PADES_BASELINE_T`     | Includes an RFC 3161 signature timestamp                           |
| `PADES_BASELINE_LT`    | Adds certificate revocation data (CRL/OCSP) for long-term validity |
| `PADES_BASELINE_LTA`   | Adds an archival document timestamp for long-term archival         |

## Visible signatures

Pass `--visible` to add a visible signature rectangle. You must also provide position
(`--vis-x`, `--vis-y`) and size (`--vis-width`, `--vis-height`) in PDF user units
(1 unit = 1/72 inch). Optionally provide `--vis-text` for custom label text
or `--vis-image` for a logo/image inside the signature box.

## JSON output

Pass `--json` (the global flag) to get machine-readable output:

```bash
omnisign --json sign -f thesis.pdf -o thesis-signed.pdf
```

Returns a JSON object with `success`, `outputFile`, `signatureId`, `signatureLevel`,
`warnings`, and optional `error` fields. Useful for CI/CD pipelines and scripting.

