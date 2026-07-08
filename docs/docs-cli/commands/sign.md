---
sidebar_position: 2
---

# sign

Sign a PDF document with a PAdES digital signature.

```
omnisign sign -f <input> -o <output> (-c <alias> | --keystore <file>) [options]
```

## Options

| Option                      | Description                                                         |
|-----------------------------|---------------------------------------------------------------------|
| `-f, --file <path>`         | **(Required)** Path to the input PDF                                |
| `-o, --output <path>`       | **(Required)** Path for the signed output PDF                       |
| `-c, --certificate <alias>` | Certificate alias to use (see `certificates list`). Required unless `--keystore` is given                  |
| `-k, --keystore <path>` | Sign with a PKCS#12 (`.p12`/`.pfx`) keystore file instead of a discovered token |
| `--keystore-password <pw>` | Password for `--keystore` (in-memory, never persisted). `-` prompts with hidden input; omit to be prompted only if the keystore is protected. Env: `OMNISIGN_KEYSTORE_PASSWORD` |
| `-r, --reason <text>`       | Reason for signing (embedded in the signature)                      |
| `--location <text>`         | Location of signing (embedded in the signature)                     |
| `--contact <text>`          | Contact information of the signer (embedded in the signature)       |
| `--no-timestamp`            | Omit the RFC 3161 timestamp — produces B-B instead of B-T or higher |
| `--allow-expired-certificate` | Sign even if the signing certificate has expired. **Such signatures fail validation.** Env: `OMNISIGN_ALLOW_EXPIRED_CERTIFICATE` |
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

## Choosing the signing key

Every `sign` needs a signing key from one of two sources:

- **A discovered token** — `--certificate <alias>` picks a certificate from a configured PKCS#11
  token or the OS certificate store (Windows / macOS). Run [`certificates list`](certificates) to see
  the aliases. **Required when `--keystore` is not used.**
- **A PKCS#12 keystore file** — `--keystore <path>` signs with a `.p12`/`.pfx` keystore directly,
  no token configuration needed. `--certificate` is then optional: omit it to use the keystore's
  single key, or pass an alias to choose a specific certificate inside a multi-key keystore.

Running `sign` with neither `--certificate` nor `--keystore` is an error.

### Keystore password

When you use `--keystore`, the password is resolved in this order:

1. `--keystore-password <value>`, or the `OMNISIGN_KEYSTORE_PASSWORD` environment variable.
2. `--keystore-password -` prompts immediately with hidden input.
3. Otherwise, if the keystore turns out to be password-protected, you are prompted with hidden input
   automatically; an unprotected keystore is opened without a prompt.

The password is kept in memory only for the run — never written to disk or the OS keychain. Prefer the
prompt or the environment variable over passing it on the command line, where it can be visible in
process listings.

## Expired certificates

By default, `sign` refuses to use a signing certificate that is past its `notAfter` date. Pass
`--allow-expired-certificate` to sign anyway:

```bash
omnisign sign -f doc.pdf -o doc-signed.pdf -c "My Certificate" --allow-expired-certificate
```

:::warning
A signature produced this way **will fail validation.** A B-level signature carries no timestamp to
prove the certificate was still valid at signing time, so validators report the expired certificate.
Use this only for narrow cases such as testing or a tolerated grace window.
:::

The same behavior can also be enabled per-profile or globally in the configuration (it lives under
`validation.allowExpiredCertificate`); the flag turns it on for a single run.

## Examples

```bash
# Sign at the default level (B-T) with a configured token certificate
omnisign sign -f thesis.pdf -o thesis-signed.pdf -c "My Qualified Certificate"

# Sign with a PKCS#12 keystore file instead of a discovered token
# (prompts for the keystore password only if it is protected)
omnisign sign -f thesis.pdf -o thesis-signed.pdf --keystore signer.p12

# Sign at B-LTA level with a specific certificate and a visible signature
omnisign sign -f thesis.pdf -o thesis-signed.pdf \
  -c "My Qualified Certificate" \
  --signature-level PADES_BASELINE_LTA \
  --reason "Author signature" \
  --visible --vis-x 50 --vis-y 700 --vis-width 200 --vis-height 50

# Sign without a timestamp (B-B only)
omnisign sign -f doc.pdf -o doc-signed.pdf -c "My Qualified Certificate" --no-timestamp
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
omnisign --json sign -f thesis.pdf -o thesis-signed.pdf -c "My Qualified Certificate"
```

Returns a JSON object with `success`, `outputFile`, `signatureId`, `signatureLevel`,
`warnings`, and optional `error` fields. Useful for CI/CD pipelines and scripting.

