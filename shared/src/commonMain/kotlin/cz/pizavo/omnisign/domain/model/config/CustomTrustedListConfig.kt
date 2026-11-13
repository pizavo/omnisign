package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * A registered external trusted list source.
 *
 * The [source] field accepts either an HTTPS URL pointing to a remote TL XML document,
 * or a `file://`-prefixed absolute path to a locally stored XML file.
 *
 * When [signingCertPath] is provided, the TL is used only while its own XML signature
 * verifies against that certificate: a TL that is unsigned, signed with another key or
 * changed after it was signed supplies no trust — strongly recommended for non-EU TLs
 * that are not reachable through the LOTL chain. Without it, the TL is trusted
 * unconditionally.
 */
@Serializable
data class CustomTrustedListConfig(
    /**
     * Human-readable label used to reference this trusted list in CLI commands and profiles.
     */
    val name: String,

    /**
     * Source of the trusted list XML.
     * Accepted formats:
     * - `https://…` — fetched online at validation time
     * - `file:///absolute/path/to/tl.xml` — loaded from the local filesystem
     */
    val source: String,

    /**
     * Optional path to the PEM or DER certificate the TL's XML signature must verify against.
     * When null the TL signature is not verified (use only for internal/development TLs).
     */
    val signingCertPath: String? = null
)

