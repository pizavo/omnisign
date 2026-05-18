package cz.pizavo.omnisign.domain.model.config

/**
 * Stable identity of a retained DSS trusted-list source.
 *
 * Used to scope the "a trusted-list refresh is in flight" signal so the UI can
 * gate exactly the right action: the Settings refresh button reacts to any id,
 * while the validation panel only waits for the ids its active configuration
 * actually needs (see [ResolvedConfig.requiredTrustedSourceIds]).
 *
 * Mirrors the identities the JVM trusted-source registry keys its retained
 * [eu.europa.esig.dss.tsl.job.TLValidationJob]s by, but lives in `commonMain`
 * so multiplatform ViewModels can reason about it without depending on the DSS
 * layer.
 */
sealed interface TrustedSourceId {

	/** The single shared EU List of Trusted Lists source. */
	data object EuLotl : TrustedSourceId

	/**
	 * A custom trusted list, identified by the same `(url, signingCertPath)` pair
	 * the registry deduplicates retained custom-list jobs by.
	 *
	 * @property url The trusted list source URL or `file://` path.
	 * @property signingCertPath Optional path to the TL signing certificate, part
	 *   of the identity because two configs differing only by signing cert are
	 *   distinct trust sources.
	 */
	data class CustomList(val url: String, val signingCertPath: String?) : TrustedSourceId
}

/**
 * The set of [TrustedSourceId]s a validation under this resolved configuration
 * actually depends on: the EU LOTL when enabled, plus every custom trusted list.
 *
 * Direct trusted certificates are intentionally excluded — they are rebuilt
 * cheaply per call and never retained, so they are never "refreshing".
 */
fun ResolvedConfig.requiredTrustedSourceIds(): Set<TrustedSourceId> = buildSet {
	if (validation.useEuLotl) add(TrustedSourceId.EuLotl)
	validation.customTrustedLists.forEach {
		add(TrustedSourceId.CustomList(it.source, it.signingCertPath))
	}
}
