---
sidebar_position: 6
---

# Troubleshooting & Support

If something goes wrong, the desktop app can produce a diagnostic log archive
you can attach to a bug report. The log and debug-logging controls described first
live in the **Help** panel — open it from the icon at the bottom of the right-hand
sidebar. A dedicated section at the end covers the most common hardware-token problem.

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

![Help panel Support section](/img/desktop/help-support-section.avif)

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

![Debug-logging indicator dot on the Help icon](/img/desktop/help-debug-dot-icon.avif)

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

## PKCS#11 tokens not detected

When a smart card or USB token doesn't appear in the signing dialog's certificate dropdown,
open the **PKCS#11 diagnostic** dialog: in the signing dialog, click the **info icon** in the
header, or use the **Show diagnostic info** link in the empty-token banner (it also appears on
the "no tokens detected" rescan toast). The dialog has three read-only sections:

- **PC/SC readers** — the card readers the OS reports, including the card ATR when one is
  inserted. An empty list usually means the platform smart-card service is stopped or no
  compatible reader is connected.
- **Candidate PKCS#11 libraries** — the middleware libraries discovery would probe right now,
  merged across OS-native sources, the drop directory, and your custom entries.
- **Drop directory** — the folder where you can copy a PKCS#11 library file for automatic
  pickup. The path is clickable and opens your file manager.

![PKCS#11 diagnostic dialog](/img/desktop/pkcs11-diagnostic-dialog.avif)

Common fixes:

1. **Insert the card / plug in the reader.** While the signing dialog is open, inserting a card
   or reader is detected automatically and the certificate list refreshes.
2. **Rescan after installing middleware.** If you installed or changed PKCS#11 middleware while
   OmniSign was running, use **Rescan tokens** in the signing dialog header — no card or reader
   event would otherwise trigger re-detection.
3. **Register the library path.** If the middleware isn't advertised to the OS (for example,
   SafeNet Authentication Client on Windows registers CSP/minidriver entries but no PKCS#11
   path), add its library under **Settings → Tokens → PKCS#11 Libraries**, or copy the library
   file into the **drop directory** shown there and in the diagnostic dialog.
4. **Load the key from a file instead.** If you have a `.p12` / `.pfx` export, use the import
   button in the signing dialog to sign without the token.
