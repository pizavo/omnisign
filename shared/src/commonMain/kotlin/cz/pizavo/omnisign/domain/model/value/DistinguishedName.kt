package cz.pizavo.omnisign.domain.model.value

/**
 * Extracts the first `CN=` RDN value from an RFC 2253 / RFC 4514 distinguished name.
 *
 * Single source of truth shared by the certificate-dropdown display
 * ([cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo] common-name rendering)
 * and the signing-critical certificate-alias derivation in the JVM data layer, so the
 * common name a user sees and the one baked into the resolution alias are always computed
 * identically.  Pure string handling — deliberately in `commonMain` so the Compose UI
 * module and the JVM token service share one implementation instead of each
 * re-implementing the parse.
 *
 * The value is trimmed but **not** emptiness-filtered: a `CN=` with an empty value yields
 * `""`, and the absence of any `CN=` RDN yields `null`.  Callers apply their own fallback
 * (the full DN, a placeholder, …) so this function imposes no display policy.
 *
 * @param dn The distinguished name, e.g. `CN=Jane Doe,O=Acme,C=CZ`.
 * @return The trimmed first `CN=` value, or `null` when no `CN=` RDN is present.
 */
fun commonNameOf(dn: String): String? =
    dn
        .split(",")
        .firstOrNull { it.trim().startsWith("CN=") }
        ?.substringAfter("CN=")
        ?.trim()
