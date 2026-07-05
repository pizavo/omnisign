package cz.pizavo.omnisign.config

/**
 * Validate the operation-gating configuration at server startup.
 *
 * Closes two fail-useless paths in [OperationsConfig], mirroring the empty-collection rejections
 * in [validateCorsConfig] and [validateAuthConfig]:
 *
 * 1. An explicit `operations.allowed: []` parses to an empty set, which would start a server that
 *    exposes no operations at all — every route returns 403, `GET /api/v1/capabilities` reports
 *    `[]`, and the web UI hides every affordance. A server that does nothing has no purpose; if it
 *    should not be reachable, do not start it.
 * 2. An explicit `operations.certificateAliases: []` while [AllowedOperation.SIGN] is enabled
 *    parses to an empty (non-null) allowlist that permits no certificate at all — every signing
 *    request is rejected with `CERTIFICATE_NOT_ALLOWED` and the certificate list comes back empty,
 *    so the enabled SIGN operation could never succeed.
 * 3. An `operations.signingKeystorePath` set while [AllowedOperation.SIGN] is *not* enabled: the
 *    keystore would never be loaded (no signing happens), so the path is a dead setting that most
 *    likely signals the operator forgot to enable SIGN.
 *
 * The empty-collection checks reject only an *explicitly empty* collection. Disabling a subset stays
 * supported:
 * `allowed: [SIGN, TIMESTAMP]` turns `VALIDATE` off and `allowed: [VALIDATE]` is validate-only.
 * Omitting `operations` resolves to the `setOf(`[AllowedOperation.VALIDATE]`)` default
 * ([OperationsConfig.allowed]); omitting `certificateAliases` (`null`) allows every discovered
 * signing certificate. An empty `certificateAliases` is left alone when SIGN is not enabled — the
 * field is meaningless without it ([OperationsConfig.certificateAliases]).
 *
 * Called from [cz.pizavo.omnisign.moduleWith] alongside the other startup config validators.
 *
 * @param operations Operation-gating configuration parsed from `server.yml`. Always present
 *   ([ServerConfig.operations] is non-null with a default), so this takes a non-null value
 *   unlike [validateCorsConfig].
 * @throws IllegalArgumentException with operator-actionable guidance when [OperationsConfig.allowed]
 *   is empty, when [OperationsConfig.certificateAliases] is empty while SIGN is enabled, or when
 *   [OperationsConfig.signingKeystorePath] is set while SIGN is not enabled.
 */
fun validateOperationsConfig(operations: OperationsConfig) {
	require(operations.allowed.isNotEmpty()) {
		"operations.allowed is empty: enable at least one of VALIDATE, SIGN, TIMESTAMP. " +
			"Omit the operations block for the validate-only default; you cannot disable everything."
	}
	require(
		AllowedOperation.SIGN !in operations.allowed ||
			operations.certificateAliases == null ||
			operations.certificateAliases.isNotEmpty(),
	) {
		"operations.certificateAliases is empty while SIGN is enabled: no certificate could ever " +
			"be used to sign, so the SIGN operation would be useless. Omit certificateAliases to " +
			"allow every discovered signing certificate, or list at least one alias."
	}
	require(
		operations.signingKeystorePath == null || AllowedOperation.SIGN in operations.allowed,
	) {
		"operations.signingKeystorePath is set but SIGN is not in operations.allowed: the signing " +
			"keystore would never be used. Add SIGN to operations.allowed, or remove " +
			"signingKeystorePath."
	}
}
