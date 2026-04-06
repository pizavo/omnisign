---
sidebar_position: 7
---

# schedule

Controls the OS-level daily job that invokes `omnisign renew` automatically, and manages
the renewal job entries stored in the configuration.

## `schedule install`

Register (or replace) the daily `omnisign renew` job with the OS scheduler
(cron on Linux/macOS, Task Scheduler on Windows).

```
omnisign schedule install [options]
```

| Option              | Description                                                                                              |
|---------------------|----------------------------------------------------------------------------------------------------------|
| `--cli-path <path>` | Absolute path to the omnisign binary. Auto-detected when omitted (may fail for `java -jar` invocations). |
| `--hour <0-23>`     | Hour of day at which the job runs. Default: `2`                                                          |
| `--minute <0-59>`   | Minute at which the job runs. Default: `0`                                                               |
| `--log-file <path>` | Absolute path to an append-only log file for renewal output.                                             |

## `schedule uninstall`

Remove the daily `omnisign renew` OS job if it exists.

```bash
omnisign schedule uninstall
```

## `schedule status`

Show whether the daily `omnisign renew` OS job is currently registered.

```bash
omnisign schedule status
```

## `schedule job` — Manage renewal jobs {#schedule-job}

Renewal jobs define which PDF files are monitored for archival timestamp expiry and
with which settings (TSA profile, buffer window, notification behavior).

| Subcommand                   | Description                      |
|------------------------------|----------------------------------|
| `schedule job add <name>`    | Add or replace a renewal job     |
| `schedule job list`          | List all configured renewal jobs |
| `schedule job remove <name>` | Remove a renewal job             |

### `schedule job add` options

| Option                  | Description                                                                                              |
|-------------------------|----------------------------------------------------------------------------------------------------------|
| `-g, --glob <pattern>`  | **(Required, repeatable)** Glob pattern matching PDF files to watch                                      |
| `-b, --buffer-days <n>` | Days before timestamp certificate expiry at which re-timestamping is triggered. Default: library default |
| `--profile <name>`      | Named configuration profile to use for TSA and revocation settings                                       |
| `--log-file <path>`     | Absolute path to an append-only log file for this job's renewal output                                   |
| `--no-notify`           | Disable OS desktop notifications for this job                                                            |

### Examples

```bash
# Add a renewal job that watches all PDFs under a directory
omnisign schedule job add thesis-archive \
  --glob "/home/user/docs/**/*.pdf" \
  --buffer-days 60 \
  --profile university

# Install the daily OS scheduler job
omnisign schedule install --hour 3 --log-file /var/log/omnisign-renew.log

# Check scheduler status
omnisign schedule status
```

