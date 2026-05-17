package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.service.TokenInfo

/**
 * Collapses raw per-library probe results into the user-visible [TokenInfo] list,
 * deduplicating by physical token serial number.
 *
 * Single source of truth for the proxy-vs-direct ordering and serial normalisation, shared
 * by [Pkcs11Discoverer] (the live discovery + cached-read paths) and
 * [Pkcs11DiagnosticsService] (the report's token list).  Both depend on this collaborator
 * rather than on each other so the dedup policy lives in exactly one place.
 */
class Pkcs11TokenInfoDeduplicator {

	/**
	 * Apply the deduplication strategy to a pre-computed list of probed candidates and emit
	 * the resulting [TokenInfo] entries.
	 *
	 * Dedup rules:
	 * 1. **Identities required** — only libraries that returned at least one token identity
	 *    contribute to the result.  Libraries that probe successfully but expose no inserted
	 *    token are surfaced only via diagnostics, not as user-visible tokens.
	 * 2. **Serial number** — the same normalised serial collapses to a single entry; direct
	 *    libraries are processed before proxy paths so direct paths win.
	 *
	 * @param probedCandidates Triples of `(display name, absolute path, identities)` —
	 *   one per candidate library, in any order.
	 * @return Deduplicated [TokenInfo] list ready to surface to the caller.
	 */
	fun buildTokenInfoList(
		probedCandidates: List<Triple<String, String, List<Pkcs11TokenIdentity>>>,
	): List<TokenInfo> {
		val withIdentities = probedCandidates.filter { it.third.isNotEmpty() }
		val sortedWithIdentities = withIdentities.sortedBy { isProxyPath(it.second) }

		val result = mutableListOf<TokenInfo>()
		val seenSerials = mutableSetOf<String>()

		for ((_, path, identities) in sortedWithIdentities) {
			for (identity in identities) {
				if (seenSerials.add(normalizeSerial(identity.serialNumber))) {
					result += TokenInfo(
						id = "pkcs11-${identity.serialNumber}",
						name = identity.label,
						type = TokenType.PKCS11,
						path = path,
						requiresPin = true,
						pkcs11SlotId = identity.slotId,
					)
				}
			}
		}

		return result
	}

	/**
	 * Return `true` when the given [path] refers to the p11-kit proxy PKCS#11 module.
	 *
	 * The proxy is a single subprocess load that covers every p11-kit-registered module
	 * with consistent slot IDs.  If the user also adds direct module paths via the app-data
	 * drop directory or `customPkcs11Libraries`, the proxy and a direct module may report
	 * the same physical token.  In that case [buildTokenInfoList] sorts proxy results last
	 * so the direct library's path wins, because direct paths typically come from explicit
	 * user intent and let us pin SunPKCS11 to a vendor-specific slot ID.
	 */
	internal fun isProxyPath(path: String): Boolean {
		val lower = path.lowercase()
		return lower.contains("p11-kit-proxy") || lower.contains("p11kitproxy")
	}
}

/**
 * Normalize a PKCS#11 token serial number for deduplication comparison.
 *
 * Different middleware implementations may report the same physical serial with
 * different padding and casing — for example, SafeNet uses null-byte padding while
 * OpenSC uses space-padding, and some middleware upper-cases the hex serial while
 * others preserve the case from the card.  This function strips all whitespace and
 * null bytes and upper-cases the result so that a serial padded with trailing null
 * bytes and one padded with trailing spaces both normalize to the same value.
 *
 * @param serial The raw serial string (already decoded from bytes, may contain
 *   residual whitespace or null-byte artifacts).
 * @return The normalized serial suitable for set-based deduplication.
 */
internal fun normalizeSerial(serial: String): String =
	serial.filterNot { it.isWhitespace() || it.code == 0 }.uppercase()
