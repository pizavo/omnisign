package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.branding.PRODUCT_NAME
import kotlinx.serialization.Serializable

/**
 * Health check response.
 *
 * @property status Always `"ok"` when the server is running.
 * @property version Application version string.
 * @property organizationName Optional deploy-time branding label of the operator running this
 *   server, set via the server's `organizationName` config; `null` when unset. Lets a monitoring probe
 *   or bare API caller identify which deployment answered.
 * @property poweredBy The fixed OmniSign product name ([PRODUCT_NAME]), always present so the
 *   attribution travels with even a bare health probe. Not configurable.
 */
@Serializable
data class HealthResponse(
	val status: String = "ok",
	val version: String,
	val organizationName: String? = null,
	val poweredBy: String = PRODUCT_NAME,
)

