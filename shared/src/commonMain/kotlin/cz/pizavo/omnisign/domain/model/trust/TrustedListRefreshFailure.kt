package cz.pizavo.omnisign.domain.model.trust

import kotlin.time.Instant

/**
 * The aggregate outcome of a trusted-list refresh in which one or more sources failed
 * to obtain usable trust — the app was offline on a cold cache, or a source's download
 * threw or returned nothing.
 *
 * Carried by [cz.pizavo.omnisign.domain.port.TrustedListRefreshPort.lastFailure] so the
 * desktop can surface a single, source-appropriate notification instead of one toast per
 * failed list. A whole refresh session collapses to one value via [of], and the EU LOTL
 * is prioritised: whenever it is among the failures it leads the message — alone, or as
 * "and other lists" when custom lists failed too — because losing the LOTL removes every
 * EU trust anchor at once.
 *
 * @property at When the failure was recorded. A distinct instant per failure keeps a
 *   `StateFlow` observer re-firing even on a repeated, otherwise-identical failure.
 */
sealed interface TrustedListRefreshFailure {

	val at: Instant

	/** Only the shared EU LOTL failed; no custom list did. */
	data class EuLotl(override val at: Instant) : TrustedListRefreshFailure

	/** The EU LOTL failed together with one or more custom trusted lists. */
	data class EuLotlAndOthers(override val at: Instant) : TrustedListRefreshFailure

	/** Exactly one custom trusted list failed and the EU LOTL did not; [name] is its human-readable label. */
	data class CustomList(val name: String, override val at: Instant) : TrustedListRefreshFailure

	/** Two or more custom trusted lists failed and the EU LOTL did not. */
	data class Multiple(override val at: Instant) : TrustedListRefreshFailure

	companion object {
		/**
		 * Collapse a refresh session's failures into a single [TrustedListRefreshFailure],
		 * prioritising the EU LOTL over any custom-list failures, or `null` when nothing failed.
		 * A LOTL failure alongside custom-list failures resolves to [EuLotlAndOthers].
		 *
		 * @param lotlFailed Whether the shared EU LOTL failed to load.
		 * @param failedCustomNames Human-readable names of the custom lists that failed.
		 * @param at When the session's failures were finalised.
		 */
		fun of(lotlFailed: Boolean, failedCustomNames: List<String>, at: Instant): TrustedListRefreshFailure? = when {
			lotlFailed && failedCustomNames.isNotEmpty() -> EuLotlAndOthers(at)
			lotlFailed -> EuLotl(at)
			failedCustomNames.size == 1 -> CustomList(failedCustomNames.single(), at)
			failedCustomNames.isNotEmpty() -> Multiple(at)
			else -> null
		}
	}
}
