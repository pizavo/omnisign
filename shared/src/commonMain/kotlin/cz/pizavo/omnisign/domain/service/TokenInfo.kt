package cz.pizavo.omnisign.domain.service

import cz.pizavo.omnisign.domain.model.config.enums.TokenType

/**
 * Information about a discovered token.
 */
data class TokenInfo(
    val id: String,
    val name: String,
    val type: TokenType,
    val path: String? = null,
    val requiresPin: Boolean = true
)


