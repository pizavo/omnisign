package cz.pizavo.omnisign.config

/**
 * Validate the operation-gating configuration at server startup.
 *
 * Closes a fail-useless path: an explicit `operations.allowed: []` parses to an empty set,
 * which would start a server that exposes no operations at all — every route returns 403,
 * `GET /api/v1/capabilities` reports `[]`, and the web UI hides every affordance. A server
 * that does nothing has no purpose; if it should not be reachable, do not start it. Mirrors
 * the empty-collection rejections in [validateCorsConfig] and [validateAuthConfig].
 *
 * Disabling a *subset* stays supported: `allowed: [SIGN, TIMESTAMP]` turns `VALIDATE` off and
 * `allowed: [VALIDATE]` is validate-only. Omitting the `operations` block resolves to the
 * `setOf(`[AllowedOperation.VALIDATE]`)` default ([OperationsConfig.allowed]), which already
 * satisfies "at least one" — only an explicitly empty set is rejected.
 *
 * Called from [cz.pizavo.omnisign.moduleWith] alongside the other startup config validators.
 *
 * @param operations Operation-gating configuration parsed from `server.yml`. Always present
 *   ([ServerConfig.operations] is non-null with a default), so this takes a non-null value
 *   unlike [validateCorsConfig].
 * @throws IllegalArgumentException with operator-actionable guidance when
 *   [OperationsConfig.allowed] is empty.
 */
fun validateOperationsConfig(operations: OperationsConfig) {
	require(operations.allowed.isNotEmpty()) {
		"operations.allowed is empty: enable at least one of VALIDATE, SIGN, TIMESTAMP. " +
			"Omit the operations block for the validate-only default; you cannot disable everything."
	}
}