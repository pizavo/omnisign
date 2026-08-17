package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.toDomainOrNull
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.IPdfObjFactory
import eu.europa.esig.dss.spi.validation.CertificateVerifier
import eu.europa.esig.dss.spi.validation.CertificateVerifierBuilder
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The PAdES baseline level [pdfBytes] actually reached, as DSS reads it back out of the document.
 *
 * Delegates to DSS's own `getDataFoundUpToLevel`, the same determination that drives the level shown
 * in a validation report, so the two can never disagree. That is what makes it worth paying for: an
 * operation that requested B-LT but embedded a DSS dictionary holding certificates and no revocation
 * data reports B-T here, and a caller that echoed its *request* back at the user would have called
 * that document B-LT.
 *
 * **[verifier] must carry the same trust anchors the operation itself used.** DSS's baseline-LT check
 * walks each certificate chain until it reaches a certificate that is self-signed *or trusted*, and
 * requires revocation data for everything below that point. A trust anchor needs no revocation data
 * and none is embedded for it, so judging the same bytes without anchors demands data that was never
 * supposed to be there and reports B-T for a conformant B-LT document. The divergence is invisible
 * whenever the anchor is a self-signed root — the walk stops there either way — and appears as soon
 * as a trusted list pins an *issuing* CA, which is the ordinary shape for a qualified TSA.
 *
 * The verifier is reduced to an offline, silent copy before use
 * ([CertificateVerifierBuilder.buildOfflineAndSilentCopy]), which keeps the trust and revocation
 * material but drops the AIA/CRL/OCSP sources: callers may hand in the very verifier their operation
 * ran with, and nothing here reaches the network. It is the same reduction DSS applies internally
 * when it builds a signature's baseline-requirements checker.
 *
 * A failure here never fails the operation that produced the document — the document exists and is
 * sound, only its description could not be read back. The failure is logged rather than swallowed,
 * because DSS being unable to re-parse bytes it has just written is an anomaly worth seeing, and the
 * `null` lets callers tell "did not reach the level" from "could not be established", which are not
 * the same thing.
 *
 * @param pdfBytes The document to inspect.
 * @param verifier The operation's certificate verifier, for its trust anchors; reduced to an offline
 *   copy here, so an online one is safe to pass.
 * @param pdfObjectFactory The PDF object factory to parse with, so that a document large enough to
 *   matter spills to disk exactly as it does everywhere else.
 * @return The level reached, or `null` when the document could not be parsed, carries no signature,
 *   or its level is outside the four PAdES baseline levels.
 */
@Suppress("TooGenericExceptionCaught")
internal fun readPadesLevel(
	pdfBytes: ByteArray,
	verifier: CertificateVerifier,
	pdfObjectFactory: IPdfObjFactory,
): SignatureLevel? =
	try {
		PDFDocumentValidator(InMemoryDocument(pdfBytes))
			.apply {
				setCertificateVerifier(CertificateVerifierBuilder(verifier).buildOfflineAndSilentCopy())
				setPdfObjFactory(pdfObjectFactory)
			}
			.signatures
			.mapNotNull { it.dataFoundUpToLevel?.toDomainOrNull() }
			.minOrNull()
	} catch (e: Exception) {
		logger.warn(e) { "Could not read the achieved PAdES level back out of the produced document" }
		null
	}
