package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier

/**
 * Result of building a [CommonCertificateVerifier] with optional trusted-list wiring.
 *
 * @property verifier The fully configured certificate verifier.
 * @property tlWarnings Non-fatal, localizable warnings from trusted-list loading (e.g., unreachable
 *   TL hosts). Rendered per-locale by the validation report; the signing/archiving paths take their
 *   English rendering.
 */
data class CertificateVerifierResult(
	val verifier: CommonCertificateVerifier,
	val tlWarnings: List<LocalizableText> = emptyList()
)
