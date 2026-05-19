---
sidebar_position: 6
---

# Troubleshooting & Support

If something goes wrong, the desktop app can produce a diagnostic log archive
you can attach to a bug report. Everything below lives in the **Help** panel —
open it from the icon at the bottom of the right-hand sidebar.

## The Support section

The Help panel's **Support** section (desktop only) groups everything you need
to report a problem:

- **Export logs** — bundles the current and rotated log files plus a small
  `diagnostics.txt` header (app version, OS, Java) into a single `.zip` and
  prompts you for a save location. This is the file to attach to a report.
- **Open folder** — reveals the log directory in your system file manager.
- **Report an issue on GitHub** — opens a pre-filled new issue with the `bug`
  and `desktop` labels. GitHub cannot attach files from a link, so attach the
  exported `.zip` to the issue yourself.
- **Debug logging** / **Extended logs** — see below.

## Enabling debug logging

By default the app only logs warnings and errors. To capture detail for a bug
report:

1. Turn on **Debug logging**. It applies immediately — no restart — and is
   remembered across restarts.
2. Reproduce the problem.
3. Use **Export logs** and attach the `.zip` to your report.
4. Turn **Debug logging** back off when you're done.

While debug logging is enabled, a small dot appears on the Help icon in the
sidebar as a reminder, even when the panel is closed.

### Extended logs (advanced)

**Extended logs** additionally lowers third-party library loggers (the DSS
signature stack and Apache) to debug level. This is very verbose and is only
useful when specifically asked for during deep diagnosis — leave it off
otherwise. It is only available while **Debug logging** is on.

## Where the logs are

Logs rotate daily and are kept for 30 days. The directory is:

| Platform | Location                                            |
|----------|-----------------------------------------------------|
| Windows  | `%LOCALAPPDATA%\omnisign\logs`                      |
| macOS    | `~/Library/Logs/omnisign`                           |
| Linux    | `$XDG_STATE_HOME/omnisign` (else `~/.local/state/omnisign`) |

The current log is `omnisign.log`; older days are gzip-compressed
(`omnisign.YYYY-MM-DD.log.gz`). **Export logs** packages these for you, so you
normally don't need to open the folder manually.

## Reporting a good issue

A useful report includes:

- What you did, what you expected, and what happened instead.
- The app version (shown at the bottom of the Help panel).
- The exported log `.zip` — ideally captured with **Debug logging** enabled
  while reproducing the problem.
