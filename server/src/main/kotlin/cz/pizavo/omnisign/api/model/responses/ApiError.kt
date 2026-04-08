package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Standard API error envelope returned for all error responses.
 *
 * @property error Error type identifier.
 * @property message Human-readable error message.
 * @property details Optional technical details.
 */
@Serializable
data class ApiError(
	val error: String,
	val message: String,
	val details: String? = null,
)

