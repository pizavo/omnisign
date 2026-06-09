package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.service.CertificateEntry
import eu.europa.esig.dss.token.DSSPrivateKeyEntry

/**
 * Select, from the private keys enumerated on a single token slot, the entry whose
 * certificate is the one the caller chose in [selected].
 *
 * A slot can expose more than one key whose certificates share a subject DN — most commonly
 * a renewed certificate kept alongside its expired predecessor (same holder, same issuer, new
 * serial and validity).  Matching on the subject DN alone is therefore ambiguous and resolves
 * to whichever key the driver enumerates first, frequently the expired one — DSS then aborts
 * the signature with "Expired certificate found".  The match is keyed instead on the issuer DN
 * together with the serial number, which uniquely identify an X.509 certificate
 * (RFC 5280 §4.1.2.2), so the chosen alias always signs with its own key.
 *
 * Both operands are compared in the same textual forms [CertificateEntry] is built from: the
 * serial number as the decimal `BigInteger.toString()` and the issuer as
 * `X500Principal.toString()`.
 *
 * @param keys The private keys enumerated from the opened slot.
 * @param selected The certificate the caller resolved by alias.
 * @return The [DSSPrivateKeyEntry] whose certificate matches [selected]; the first key when none
 *   matches — a best effort preserving single-certificate behaviour; or null when [keys] is empty.
 */
internal fun selectSigningKey(
	keys: List<DSSPrivateKeyEntry>,
	selected: CertificateEntry,
): DSSPrivateKeyEntry? =
	keys.find { key ->
		val certificate = key.certificate.certificate
		certificate.serialNumber.toString() == selected.serialNumber &&
			certificate.issuerX500Principal.toString() == selected.issuerDN
	} ?: keys.firstOrNull()
