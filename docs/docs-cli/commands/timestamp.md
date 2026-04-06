---
sidebar_position: 3
---

# timestamp

Extend a signed PDF to a higher PAdES level by adding timestamps and revocation data.

```
omnisign timestamp -f <input> -o <output> [options]
```

## Supported promotion paths

| From            | To              | What happens                              |
|-----------------|-----------------|-------------------------------------------|
| B-B             | B-T             | Adds a document timestamp                 |
| B-T             | B-LT            | Embeds certificate revocation data        |
| B-LT            | B-LTA           | Adds an archival document timestamp       |
| B-LTA           | B-LTA           | Re-timestamps for archival renewal        |

## Options

| Option                | Description                                                                                                     |
|-----------------------|-----------------------------------------------------------------------------------------------------------------|
| `-f, --file <path>`   | **(Required)** Path to the signed input PDF                                                                     |
| `-o, --output <path>` | **(Required)** Path for the extended output PDF                                                                 |
| `-l, --level <level>` | Target PAdES level: `PADES_BASELINE_T`, `PADES_BASELINE_LT`, `PADES_BASELINE_LTA` (default: `PADES_BASELINE_T`) |
| `--profile <name>`    | Use a named configuration profile for this operation                                                            |

All [config overrides](../configuration/config-overrides) are supported.

## Examples

```bash
# Add a document timestamp (B-B → B-T)
omnisign timestamp -f signed.pdf -o signed-t.pdf

# Embed revocation data (B-T → B-LT)
omnisign timestamp -f signed-t.pdf -o signed-lt.pdf --level PADES_BASELINE_LT

# Add archival timestamp (B-LT → B-LTA)
omnisign timestamp -f signed-lt.pdf -o signed-lta.pdf --level PADES_BASELINE_LTA

# Re-timestamp for archival renewal (B-LTA → B-LTA)
omnisign timestamp -f signed-lta.pdf -o signed-renewed.pdf --level PADES_BASELINE_LTA
```

## JSON output

Pass `--json` (the global flag) to get machine-readable output:

```bash
omnisign --json timestamp -f signed.pdf -o signed-t.pdf
```

Returns a JSON object with `success`, `outputFile`, `newSignatureLevel`,
`warnings`, and optional `error` fields.

