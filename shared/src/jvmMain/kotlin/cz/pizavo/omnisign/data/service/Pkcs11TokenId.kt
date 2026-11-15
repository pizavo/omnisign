package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo

/**
 * Prefix of the [TokenInfo.id] of a PKCS#11 token, which [pkcs11TokenId] follows with the
 * token's serial number.
 */
private const val PKCS11_TOKEN_ID_PREFIX = "pkcs11-"

/**
 * The [TokenInfo.id] of the PKCS#11 token whose serial number is [serialNumber]:
 * `pkcs11-<serial>`.
 *
 * The serial names the physical token, whichever slot or reader it sits in, so the id, and the
 * certificate aliases [pkcs11CertAlias] builds from it, stay the same when the token moves.
 *
 * @param serialNumber The token's serial number as `C_GetTokenInfo` reports it, trimmed.
 * @return The id of that token.
 */
internal fun pkcs11TokenId(serialNumber: String): String = PKCS11_TOKEN_ID_PREFIX + serialNumber

/**
 * The serial number [pkcs11TokenId] put into this token's id, or `null` when this is not a
 * PKCS#11 token whose id has that form.
 */
internal fun TokenInfo.pkcs11TokenSerial(): String? =
	id.takeIf { type == TokenType.PKCS11 && it.startsWith(PKCS11_TOKEN_ID_PREFIX) }
		?.removePrefix(PKCS11_TOKEN_ID_PREFIX)
