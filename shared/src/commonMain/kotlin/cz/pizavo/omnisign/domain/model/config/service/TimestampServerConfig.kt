package cz.pizavo.omnisign.domain.model.config.service

import kotlinx.serialization.Serializable

/**
 * Timestamp server configuration.
 */
@Serializable
data class TimestampServerConfig(
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val timeout: Int = 30000
)

