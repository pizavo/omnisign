package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.service.pkcs11CertAlias
import cz.pizavo.omnisign.domain.service.TokenInfo
import eu.europa.esig.dss.token.DSSPrivateKeyEntry

/**
 * Resolve, from the private keys enumerated on a single opened token, the entry the caller
 * chose by [certificateAlias].
 *
 * The collapsed sign path opens the token once and reads its keys once (a single `C_Login`,
 * hence a single secure-PIN-pad prompt on middleware that drives its own pad), then hands that
 * one enumeration here.  Each key's certificate is mapped back to its deterministic alias via
 * [pkcs11CertAlias] — the same function that produced the alias the caller selected — so the
 * match round-trips exactly, including the serial number that distinguishes a renewed
 * certificate from its expired predecessor on the same slot.
 *
 * Unlike [selectSigningKey] (which matches a fully-known [cz.pizavo.omnisign.domain.service.CertificateEntry]
 * and falls back to the first key), a requested alias that is **absent** from [keys] returns
 * `null` rather than the first key, so [DssSigningRepository] can retry on another slot instead
 * of silently signing with the wrong certificate.
 *
 * @param keys Private keys enumerated from the opened token (read exactly once by the caller).
 * @param certificateAlias The alias the caller chose, or `null` to take the sole / first key.
 * @param tokenInfo Source the keys were opened from; contributes the alias's `@<id>` suffix.
 * @return The [DSSPrivateKeyEntry] whose certificate alias equals [certificateAlias]; the first
 *   key when [certificateAlias] is `null`; or `null` when a requested alias matches no key, or
 *   [keys] is empty.
 */
internal fun selectSigningKeyByAlias(
	keys: List<DSSPrivateKeyEntry>,
	certificateAlias: String?,
	tokenInfo: TokenInfo,
): DSSPrivateKeyEntry? {
	if (certificateAlias == null) return keys.firstOrNull()
	return keys.firstOrNull { pkcs11CertAlias(it.certificate.certificate, tokenInfo) == certificateAlias }
}
