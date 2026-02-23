package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * A registered external trusted list source.
 *
 * The [source] field accepts either an HTTPS URL pointing to a remote TL XML document,
 * or a `file://`-prefixed absolute path to a locally stored XML file.
 *
 * When [signingCertPath] is provided, DSS will verify the TL's own XML signature
 * against that certificate instead of trusting the TL unconditionally — strongly
 * recommended for non-EU TLs that are not reachable through the LOTL chain.
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
     * Optional path to the PEM or DER certificate used to verify the TL's XML signature.
     * When null the TL signature is not verified (use only for internal/development TLs).
     */
    val signingCertPath: String? = null
)

