package cz.pizavo.omnisign.domain.model.config.service

import kotlinx.serialization.Serializable

/**
 * OCSP (Online Certificate Status Protocol) configuration.
 */
@Serializable
data class OcspConfig(
    val timeout: Int = 30000
)

