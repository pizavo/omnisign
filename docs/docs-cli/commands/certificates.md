---
sidebar_position: 6
---

# certificates

Discover and inspect available signing certificates from all configured token sources.

## `certificates list`

Lists all certificates available from configured token sources — PKCS#12 files,
PKCS#11 hardware tokens, Windows Certificate Store, macOS Keychain, etc.

The **alias** shown here is the value to pass to [`sign --certificate <alias>`](sign).

```bash
omnisign certificates list
```

The discovery process probes:

1. **OS-native stores** — Windows Certificate Store (`MSCAPI`), macOS Keychain
2. **PKCS#11 hardware tokens** — smart cards, USB tokens (e.g., YubiKey, SafeNet)
3. **PKCS#12 files** — `.p12` / `.pfx` keystores configured in the application
4. **Custom PKCS#11 libraries** — registered via [`config pkcs11 add`](../configuration/pkcs11)

:::tip
If your token is not detected, register its PKCS#11 middleware library with
`omnisign config pkcs11 add --name <label> --path /path/to/middleware.so`.
:::

## Token warnings

When a token source cannot be accessed (e.g. a smart card is not inserted, a PKCS#12 file
has an incorrect password, or a PKCS#11 library cannot be loaded), a per-token warning is
printed to stderr. These warnings explain why expected certificates may be absent from the
listing.

## JSON output

Pass `--json` (the global flag) to get machine-readable output:

```bash
omnisign --json certificates list
```

Returns a JSON object with `success`, `certificates` (each with `alias`, `subjectDN`,
`issuerDN`, `validFrom`, `validTo`, `tokenType`, `keyUsages`), `tokenWarnings`,
and optional `error` fields.

