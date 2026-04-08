package cz.pizavo.omnisign.config

/**
 * Operations that the server administrator can enable or disable via [ServerConfig.allowedOperations].
 *
 * By default only [VALIDATE] and [TIMESTAMP] are enabled because they do not require
 * private key material from the client. [SIGN] is opt-in for institutional deployments
 * where the server holds an HSM or seal certificate.
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

