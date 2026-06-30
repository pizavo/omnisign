package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.value.commonNameOf
import cz.pizavo.omnisign.domain.service.TokenInfo
import java.security.cert.X509Certificate

/**
 * Radix for rendering a certificate serial number in the deterministic alias.
 */
private const val RADIX_HEX = 16

/**
 * Deterministic certificate alias: `<CN>-<serialHex>@<tokenInfo.id>`.
 *
 * The leading `<CN>-<serialHex>` is derived purely from the certificate; the trailing
 * `@<tokenInfo.id>` records *which source* the certificate was read from.  [TokenInfo.id]
 * is stable for a given source — `pkcs11-<tokenSerial>` for a hardware token (the physical
 * token serial, never the transient slot), `windows-my` / `macos-keychain` for the OS stores,
 * `file-…` for an imported keystore — so the same physical certificate yields the same alias
 * whether it is enumerated by the no-login probe, the logged-in keystore, or the signing token.
 *
 * This is the single source of truth shared by [DssTokenService] (which builds
 * [cz.pizavo.omnisign.domain.service.CertificateEntry]s) and
 * [cz.pizavo.omnisign.data.repository.selectSigningKeyByAlias] (which resolves the chosen alias
 * back to a signing key on a single keystore enumeration), so listing and signing always agree
 * on the alias of a given certificate.
 *
 * @param cert Certificate to derive the alias from.
 * @param tokenInfo Source the certificate was read from; contributes the stable `@<id>` suffix.
 * @return The deterministic alias for [cert] on [tokenInfo].
 */
internal fun pkcs11CertAlias(cert: X509Certificate, tokenInfo: TokenInfo): String {
	val cn = commonNameOf(cert.subjectX500Principal.name) ?: "certificate"
	return "$cn-${cert.serialNumber.toString(RADIX_HEX)}@${tokenInfo.id}"
}
