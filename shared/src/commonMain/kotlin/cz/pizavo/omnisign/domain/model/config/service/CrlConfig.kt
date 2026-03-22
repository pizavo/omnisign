package cz.pizavo.omnisign.domain.model.config.service

import kotlinx.serialization.Serializable

/**
 * CRL (Certificate Revocation List) configuration.
 */
@Serializable
data class CrlConfig(
    val timeout: Int = 30000
)

