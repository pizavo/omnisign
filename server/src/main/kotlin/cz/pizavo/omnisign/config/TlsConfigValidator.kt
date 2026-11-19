package cz.pizavo.omnisign.config

/**
 * Validate the protocol versions of the `tls` block at server startup.
 *
 * [TlsConfig.enabledProtocols] must list at least one version, and only versions in
 * [TlsConfig.SUPPORTED_PROTOCOLS]. With an empty list the connector could complete no handshake; a
 * name the JVM does not know, a misspelt one say, would make every connection fail when the connector
 * sets it on the SSL engine; and an older version such as `TLSv1.1` is not one the server offers.
 * Each fails startup instead, with a message that names the versions it accepts.
 *
 * Called from [cz.pizavo.omnisign.moduleWith] alongside the other startup config validators, so a bad
 * list fails startup before the connector binds its port.
 *
 * @param tls The `tls` block parsed from `server.yml`, or `null` when there is none.
 * @throws IllegalArgumentException When [TlsConfig.enabledProtocols] is empty or lists a version that
 *   [TlsConfig.SUPPORTED_PROTOCOLS] does not.
 */
fun validateTlsConfig(tls: TlsConfig?) {
	if (tls == null) return
	val accepted = TlsConfig.SUPPORTED_PROTOCOLS.joinToString(" and ")
	require(tls.enabledProtocols.isNotEmpty()) {
		"tls.enabledProtocols is empty: list $accepted, or one of them, or omit the field to offer both."
	}
	val unsupported = tls.enabledProtocols.filterNot { it in TlsConfig.SUPPORTED_PROTOCOLS }
	require(unsupported.isEmpty()) {
		"tls.enabledProtocols lists ${unsupported.joinToString()}, which the server does not offer: " +
			"list $accepted, or one of them, or omit the field to offer both."
	}
}
