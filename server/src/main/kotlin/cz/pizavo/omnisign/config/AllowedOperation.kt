package cz.pizavo.omnisign.config

/**
 * Operations that the server administrator can enable or disable via [OperationsConfig.allowed].
 *
 * By default only [VALIDATE] is enabled — it exposes neither signing material nor a
 * timestamping endpoint. [SIGN] and [TIMESTAMP] are opt-in for institutional deployments
 * where the server holds an HSM or seal certificate and a pre-configured TSA.
 */
enum class AllowedOperation {

	/**
	 * PDF signing via a server-side certificate or HSM token.
	 *
	 * Disabled by default — enabling this without authentication exposes the configured
	 * signing certificates to every network-reachable client.
	 */
	SIGN,

	/**
	 * PDF signature validation (stateless, no secrets required).
	 */
	VALIDATE,

	/**
	 * PDF timestamping / signature extension using the server's pre-configured TSA.
	 */
	TIMESTAMP,
}

