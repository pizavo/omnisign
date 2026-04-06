---
sidebar_position: 4
---

# renew

Execute configured renewal jobs — checks each B-LTA PDF tracked by renewal jobs and
re-timestamps in-place any file whose archival timestamp is nearing expiry.

Intended to be invoked daily by the OS scheduler registered via
[`schedule install`](schedule#schedule-install), but can also be run manually at any time.

```
omnisign renew [options]
```

## Options

| Option             | Description                                                            |
|--------------------|------------------------------------------------------------------------|
| `-j, --job <name>` | Run only the named renewal job. Runs all configured jobs when omitted. |
| `--dry-run`        | Report which files need renewal without modifying any file.            |

## Examples

```bash
# Run all renewal jobs
omnisign renew

# Dry-run to see what would be renewed
omnisign renew --dry-run

# Run a single named job
omnisign renew --job university
```

## OS notifications

When a renewal job has notifications enabled (the default), OmniSign fires an OS desktop
notification after the job completes:

- **Success** — `"N file(s) successfully re-timestamped."`
- **Partial failure** — `"N re-timestamped, M error(s)."`
- **Full failure** — `"N file(s) could not be re-timestamped. Digital continuity may be at risk."`

Disable notifications per job with `schedule job add --no-notify` (recommended for headless
server deployments).

## JSON output

Pass `--json` (the global flag) to get machine-readable output:

```bash
omnisign --json renew
```

Returns a JSON object with `success`, `checked`, `renewed`, `skipped`, `errors`,
`dryRun`, `jobs` (with per-file statuses), and optional `error` fields.

