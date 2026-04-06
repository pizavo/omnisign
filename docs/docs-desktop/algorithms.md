---
sidebar_position: 4
---

# Algorithms

OmniSign supports a wide range of hash (digest) and encryption (signing key) algorithms
for PAdES digital signatures. The actual signature algorithm used by the underlying DSS
library is a combination of the selected hash algorithm and the encryption algorithm of
the signing certificate.

## Hash algorithms

The hash algorithm controls the digest computed over the signed portion of the document.

| Algorithm  | Family             | Output  | Description                     |
|------------|--------------------|:-------:|---------------------------------|
| SHA-256    | SHA-2              | 256-bit | Widely recommended baseline.    |
| SHA-384    | SHA-2              | 384-bit | Stronger SHA-2 variant.         |
| SHA-512    | SHA-2              | 512-bit | Strongest SHA-2 variant.        |
| SHA3-256   | SHA-3 (Keccak)     | 256-bit | NIST standardised 2015.         |
| SHA3-384   | SHA-3 (Keccak)     | 384-bit | SHA-3 family.                   |
| SHA3-512   | SHA-3 (Keccak)     | 512-bit | SHA-3 family.                   |
| Whirlpool  | ISO/IEC 10118-3    | 512-bit | Miyaguchi–Preneel construction. |
| RIPEMD-160 | RIPE / ISO 10118-3 | 160-bit | RIPE consortium.                |

### Platform notes

- **SHA-3, Whirlpool, RIPEMD-160** are **not supported by the Windows Certificate Store**
  (Windows CNG does not implement them). Use a PKCS#11 or file-based (PKCS#12) token when
  selecting these algorithms.
- **Whirlpool** is not in the ETSI/eIDAS recommended algorithm list; it is supported by
  DSS but uncommon in practice.
- **RIPEMD-160** has only 160-bit output, offering lower collision resistance than SHA-256.
  Use only when interoperability requires it.

### ETSI expiration dates

Some algorithms are considered expired under the DSS default ETSI validation policy
(ETSI TS 119 312). Signing with an expired algorithm may trigger validation warnings or
failures depending on the [algorithm constraint settings](guides/settings.md#algorithm-constraints).

| Algorithm   | Expiration date |
|-------------|-----------------|
| RIPEMD-160  | 2014-08-01      |
| Whirlpool   | 2020-12-01      |
| All others  | No expiration   |

## Encryption algorithms

The encryption algorithm is determined by the signing certificate's key type. OmniSign
auto-detects it from the certificate but also allows manual override in settings and
profiles.

| Algorithm   | Description                                                                                                        |
|-------------|--------------------------------------------------------------------------------------------------------------------|
| RSA         | RSA PKCS#1 v1.5 — widely supported, standard on software keystores and PKCS#12 files.                              |
| RSA-PSS     | RSA-PSS (RSASSA-PSS) — probabilistic padding, stronger than PKCS#1 v1.5; eIDAS-recommended for new RSA signatures. |
| ECDSA       | ECDSA (DER-encoded) — elliptic-curve DSA; compact keys with equivalent security to RSA at larger sizes.            |
| Plain-ECDSA | ECDSA plain (r‖s) — same cryptography as ECDSA with different signature encoding; common on smartcard/CVC tokens.  |
| DSA         | DSA (FIPS 186) — legacy algorithm; provided for interoperability, RSA or ECDSA preferred.                          |
| EdDSA       | EdDSA (Ed25519/Ed448) — modern deterministic Edwards-curve algorithm.                                              |

### Notes

- **EdDSA** has an intrinsic hash algorithm that cannot be overridden — Ed25519 uses
  SHA-512, Ed448 uses SHAKE256. The hash algorithm selection is ignored when EdDSA is active.
- **Plain-ECDSA** uses r‖s integer encoding instead of DER. Use only when your token
  produces this format; most software tokens use standard DER ECDSA.
- **DSA** keys are rare on modern tokens; prefer ECDSA or RSA for new signatures.

## Compatibility matrix

Not every hash algorithm works with every encryption algorithm. The table below shows
which combinations are valid for PAdES signing.

| Hash ↓ \ Encryption → | RSA | RSA-PSS | ECDSA | Plain-ECDSA | DSA |   EdDSA   |
|-----------------------|:---:|:-------:|:-----:|:-----------:|:---:|:---------:|
| SHA-256               |  ✅  |    ✅    |   ✅   |      ✅      |  ✅  | *(fixed)* |
| SHA-384               |  ✅  |    ✅    |   ✅   |      ✅      |  ✅  | *(fixed)* |
| SHA-512               |  ✅  |    ✅    |   ✅   |      ✅      |  ✅  | *(fixed)* |
| SHA3-256              |  ✅  |    ✅    |   ✅   |      ✅      |  ✅  | *(fixed)* |
| SHA3-384              |  ✅  |    ✅    |   ✅   |      ✅      |  ✅  | *(fixed)* |
| SHA3-512              |  ✅  |    ✅    |   ✅   |      ✅      |  ✅  | *(fixed)* |
| Whirlpool             |  ❌  |    ❌    |   ❌   |      ❌      |  ❌  | *(fixed)* |
| RIPEMD-160            |  ✅  |    ✅    |   ✅   |      ✅      |  ❌  | *(fixed)* |

*(fixed)* — EdDSA uses an intrinsic hash; external selection is ignored.

:::caution Whirlpool
Whirlpool cannot be combined with any encryption algorithm for PAdES signing. This is a
limitation of the EU DSS library (version 6.3): while `DigestAlgorithm.WHIRLPOOL` exists
for standalone hashing, there is no corresponding `SignatureAlgorithm` entry pairing
Whirlpool with RSA, ECDSA, or any other key type. This restriction applies on **all
platforms** — including Linux, macOS, and hardware QSCDs — not just Windows.

Whirlpool is included in OmniSign's hash algorithm list for completeness and for potential
future use if DSS adds signing support.
:::

:::tip
When in doubt, **SHA-256 + RSA** or **SHA-256 + ECDSA** are the safest choices for
maximum interoperability with validators, timestamp authorities, and EU trust services.
:::



