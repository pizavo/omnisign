---
sidebar_position: 5
---

# algorithms

List supported cryptographic algorithms with metadata.

## `algorithms hash list`

Lists all supported hash algorithms with their ETSI TS 119 312 expiration dates and usage notes.
The algorithm name shown here is the value accepted by `--hash-algorithm` throughout the CLI.

```bash
omnisign algorithms hash list
```

### Supported hash algorithms

| Algorithm   | Notes                                                                  |
|-------------|------------------------------------------------------------------------|
| `SHA256`    | Default. Widely supported, recommended for general use.                |
| `SHA384`    | Stronger variant of SHA-2.                                             |
| `SHA512`    | Strongest SHA-2 variant. Recommended for long-lived signatures.        |
| `SHA3_256`  | SHA-3 family. Not yet universally supported by all TSAs.               |
| `SHA3_384`  | SHA-3 family.                                                          |
| `SHA3_512`  | SHA-3 family.                                                          |
| `WHIRLPOOL` | ISO/IEC 10118-3. Limited TSA and verifier support.                     |
| `RIPEMD160` | Legacy. Expiring in ETSI recommendations; use only for compatibility.  |

## `algorithms encryption list`

Lists all supported encryption (signing key) algorithms.
The algorithm name shown here is the value accepted by `--encryption-algorithm` throughout the CLI.

```bash
omnisign algorithms encryption list
```

### Supported encryption algorithms

| Algorithm     | Notes                                                                  |
|---------------|------------------------------------------------------------------------|
| `RSA`         | RSA PKCS#1 v1.5. Most common on software tokens.                       |
| `RSA_SSA_PSS` | RSA-PSS probabilistic padding. Preferred by ETSI for new signatures.   |
| `ECDSA`       | Elliptic-Curve Digital Signature Algorithm (DER-encoded).              |
| `PLAIN_ECDSA` | ECDSA with plain (IEEE P1363) encoding.                                |
| `DSA`         | Digital Signature Algorithm. Legacy; declining support.                |
| `EDDSA`       | Edwards-curve Digital Signature Algorithm (Ed25519/Ed448). Fixed hash. |

