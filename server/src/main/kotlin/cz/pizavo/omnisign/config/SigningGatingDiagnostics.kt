package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel

/**
 * List the signing targets — the global default and named profiles — whose PAdES level mandates
 * an RFC 3161 timestamp this server cannot issue, so every sign request against them is rejected
 * with `TIMESTAMP_NOT_ALLOWED`.
 *
 * A target is unsatisfiable only when [AllowedOperation.SIGN] is enabled but
 * [AllowedOperation.TIMESTAMP] is not: signing is reachable, yet any level above
 * [SignatureLevel.PADES_BASELINE_B] embeds a signature timestamp the server has no way to obtain.
 * When SIGN is disabled (no signing at all) or TIMESTAMP is enabled (timestamps available) the
 * result is empty.
 *
 * Unlike [validateOperationsConfig] this never throws — a server with some working profiles is
 * still useful, so the caller emits a startup warning rather than refusing to boot. It tells the
 * operator at startup what the client's `TimestampingUnavailable` block and the server's
 * `TIMESTAMP_NOT_ALLOWED` response would otherwise only reveal one failed request at a time.
 *
 * The effective level mirrors [cz.pizavo.omnisign.domain.model.config.ResolvedConfig] resolution
 * for the level dimension: a profile's own signature level when set, otherwise the global default
 * level. A profile that overrides its level back down to [SignatureLevel.PADES_BASELINE_B] is
 * satisfiable even when the global default is higher.
 *
 * @param operations Operation gating parsed from `server.yml`.
 * @param signingConfig Provider signing policy (global default + named profiles) from `signing.yml`.
 * @return Human-readable labels for each unsatisfiable target — e.g. `"the global default
 *   (PADES_BASELINE_LTA)"` and `"profile 'archival' (PADES_BASELINE_LTA)"` — global default first,
 *   then profiles in declaration order. Empty when nothing is unsatisfiable.
 */
fun unsatisfiableSigningTargets(operations: OperationsConfig, signingConfig: AppConfig): List<String> {
	if (AllowedOperation.SIGN !in operations.allowed) return emptyList()
	if (AllowedOperation.TIMESTAMP in operations.allowed) return emptyList()

	val globalLevel = signingConfig.global.defaultSignatureLevel
	val targets = mutableListOf<String>()
	if (globalLevel != SignatureLevel.PADES_BASELINE_B) {
		targets += "the global default ($globalLevel)"
	}
	signingConfig.profiles.forEach { (name, profile) ->
		val level = profile.signatureLevel ?: globalLevel
		if (level != SignatureLevel.PADES_BASELINE_B) {
			targets += "profile '$name' ($level)"
		}
	}
	return targets
}
