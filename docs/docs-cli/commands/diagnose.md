---
sidebar_position: 8
---

# diagnose

Run read-only diagnostic probes over OmniSign subsystems. Diagnostic subcommands never modify any
state and produce structured reports intended for ad-hoc troubleshooting and bug-report attachments.

## `diagnose pkcs11`

Print a verbose, ground-truth PKCS#11 discovery report for the current host. Use it when a smart
card or token is not showing up in `certificates list` or the signing flow, or to compare behaviour
across machines.

```bash
omnisign diagnose pkcs11
```

The command takes no options. It is strictly read-only: it does not trigger application warmup, does
not touch the keystore, and never calls `C_Login` (no PIN is requested).

### What the report covers

- **Environment** — OS, JVM bitness, Java version, `java.home`, classpath size, and whether
  `--enable-native-access` is present.
- **Candidate sources** — the PKCS#11 libraries discovery would consider, grouped by layer:
  OS-native discovery, the drop directory, and user-supplied (`config pkcs11 add`) entries — plus
  the merged, de-duplicated candidate list.
- **p11-kit truth (Linux)** — `p11-kit` version and `list-modules` / `list-tokens` output, the
  out-of-process source of truth on Linux.
- **PC/SC readers** — the card readers the OS reports (empty usually means `pcscd`/winscard is
  unreachable or no reader is connected).
- **Per-candidate probes** — each library is probed in an isolated subprocess; the report shows the
  outcome (`SUCCESS` / `CRASHED` / `TIMED OUT` / `NO COMMAND`), wall-clock time, and any token
  identities found.
- **Final tokens** — the tokens exactly as discovery would emit them, including whether each
  requires a PIN.
- **No-login enumeration** — whether each token's certificates are readable without a PIN.

### Using it in a bug report

The output is intentionally verbose and copy-paste friendly. Capture it alongside an exported log
archive when reporting a token-discovery problem:

```bash
omnisign diagnose pkcs11 > pkcs11-diagnostic.txt
```

:::tip
If a candidate library is listed but its probe shows `CRASHED` or `TIMED OUT`, the middleware is
present but failing to initialise — often a 32-bit/64-bit mismatch or a missing native dependency.
A library that is missing entirely usually means it needs registering with
[`config pkcs11 add`](../configuration/pkcs11) or dropping into the drop directory.
:::
