---
sidebar_position: 4
---

# PKCS#11 Libraries

Register custom PKCS#11 middleware library paths for hardware token discovery.
Libraries registered here are merged into token discovery alongside the OS-native
autodiscovery results and the built-in fallback candidate list.

## `config pkcs11 add`

| Option                 | Description                                                                            |
|------------------------|----------------------------------------------------------------------------------------|
| `-n, --name <name>`    | **(Required)** Unique label for this library (used in `pkcs11 remove`)                 |
| `-p, --path <path>`    | **(Required)** Absolute path to the PKCS#11 shared library (`.dll` / `.so` / `.dylib`) |
| `--protected-pin-pad`  | The middleware collects the PIN on its own secure pad / virtual keyboard; OmniSign shows no PIN dialog for this library. See [Protected PIN entry](#protected-pin-entry) |

## `config pkcs11 list`

List all registered custom PKCS#11 middleware libraries.

```bash
omnisign config pkcs11 list
```

## `config pkcs11 remove <name>`

Remove a registered PKCS#11 middleware library.

```bash
omnisign config pkcs11 remove safenet
```

## Protected PIN entry

Some middleware collects the PIN on the token's **own** secure pad or an on-screen virtual keyboard —
a *protected authentication path*, common with national eID clients. Register those libraries with
`--protected-pin-pad` so OmniSign suppresses its own PIN dialog and lets the module drive PIN entry;
otherwise you would be prompted twice, once by OmniSign and once by the module.

```bash
omnisign config pkcs11 add --name myeid --path /usr/lib/vendor-pkcs11.so --protected-pin-pad
```

`config pkcs11 list` marks such a library with an extra line:

```
Registered PKCS#11 libraries:
  ● myeid  ✅
    Path: /usr/lib/vendor-pkcs11.so
    PIN entry: delegated to the module's own secure pad
```

## Examples

```bash
# Register a PKCS#11 library installed in a non-standard location
omnisign config pkcs11 add --name safenet --path /usr/lib/libeTPkcs11.so

# On Windows
omnisign config pkcs11 add --name safenet --path "C:\Windows\System32\eTPKCS11.dll"

# On macOS
omnisign config pkcs11 add --name opensc --path /Library/OpenSC/lib/opensc-pkcs11.so
```

## Common PKCS#11 middleware paths

| Token / Provider | Linux                       | Windows                                            | macOS                                  |
|------------------|-----------------------------|----------------------------------------------------|----------------------------------------|
| OpenSC           | `/usr/lib/opensc-pkcs11.so` | `C:\Program Files\OpenSC\pkcs11\opensc-pkcs11.dll` | `/Library/OpenSC/lib/opensc-pkcs11.so` |
| SafeNet / Thales | `/usr/lib/libeTPkcs11.so`   | `C:\Windows\System32\eTPKCS11.dll`                 | `/usr/local/lib/libeTPkcs11.dylib`     |
| YubiKey (YKCS11) | `/usr/lib/libykcs11.so`     | `C:\Program Files\Yubico\ykcs11.dll`               | `/usr/local/lib/libykcs11.dylib`       |

:::tip
OmniSign automatically discovers common PKCS#11 libraries on your system. You only need
to register a library manually if it's installed in a non-standard location or if
autodiscovery doesn't find it.
:::

