package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.detailedreport.DetailedReport
import eu.europa.esig.dss.detailedreport.jaxb.XmlAOV
import eu.europa.esig.dss.detailedreport.jaxb.XmlBasicBuildingBlocks
import eu.europa.esig.dss.detailedreport.jaxb.XmlCRS
import eu.europa.esig.dss.detailedreport.jaxb.XmlCV
import eu.europa.esig.dss.detailedreport.jaxb.XmlConclusion
import eu.europa.esig.dss.detailedreport.jaxb.XmlDetailedReport
import eu.europa.esig.dss.detailedreport.jaxb.XmlISC
import eu.europa.esig.dss.detailedreport.jaxb.XmlRAC
import eu.europa.esig.dss.detailedreport.jaxb.XmlSAV
import eu.europa.esig.dss.detailedreport.jaxb.XmlSubXCV
import eu.europa.esig.dss.detailedreport.jaxb.XmlXCV
import eu.europa.esig.dss.diagnostic.DiagnosticData
import eu.europa.esig.dss.diagnostic.jaxb.XmlDiagnosticData
import eu.europa.esig.dss.diagnostic.jaxb.XmlRevocation
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.Indication
import eu.europa.esig.dss.enumerations.SubIndication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Verifies that a revocation token is reported as accepted only when its acceptance check passed
 * for the requested certificate, in the requested token's basic building blocks, and its own basic
 * validation, where DSS ran one, came out as DSS's long-term validation requires.
 */
class AcceptedRevocationIdsTest : FunSpec({

	/** Diagnostic data that holds no revocation tokens. */
	val noDiagnostics = DiagnosticData(XmlDiagnosticData())

	/** A conclusion of [indication], for the reason [subIndication] when given. */
	fun concluded(indication: Indication, subIndication: SubIndication? = null) = XmlConclusion().apply {
		this.indication = indication
		this.subIndication = subIndication
	}

	/** A revocation acceptance check of the token [id] that ended with [indication]. */
	fun rac(id: String, indication: Indication?) = XmlRAC().apply {
		this.id = id
		conclusion = indication?.let { concluded(it) }
	}

	/** The certificate validation of [certificateId], selecting among [racs]; no selector when null. */
	fun subXcv(certificateId: String, racs: List<XmlRAC>?) = XmlSubXCV().apply {
		id = certificateId
		crs = racs?.let { XmlCRS().apply { id = certificateId; rac.addAll(it) } }
	}

	/** Basic building blocks of the signature [tokenId] that validate [subXcvs]. */
	fun signatureBlocks(tokenId: String, vararg subXcvs: XmlSubXCV) = XmlBasicBuildingBlocks().apply {
		id = tokenId
		type = Context.SIGNATURE
		xcv = XmlXCV().apply { subXCV.addAll(subXcvs) }
	}

	/**
	 * Basic building blocks of the revocation token [revocationId] whose certificate validation ends
	 * with [certificateValidation] and whose signature acceptance validation ends with
	 * [signatureAcceptance]. The identification of its signing certificate and its cryptographic
	 * verification pass, its algorithm obsolescence check raises nothing, and the overall conclusion
	 * is that of the last block that did not pass, as DSS sets it.
	 */
	fun revocationBlocks(
		revocationId: String,
		certificateValidation: XmlConclusion,
		signatureAcceptance: XmlConclusion = concluded(Indication.PASSED),
	) = XmlBasicBuildingBlocks().apply {
		id = revocationId
		type = Context.REVOCATION
		isc = XmlISC().apply { conclusion = concluded(Indication.PASSED) }
		aov = XmlAOV()
		xcv = XmlXCV().apply { conclusion = certificateValidation }
		cv = XmlCV().apply { conclusion = concluded(Indication.PASSED) }
		sav = XmlSAV().apply { conclusion = signatureAcceptance }
		conclusion = listOf(certificateValidation, signatureAcceptance)
			.lastOrNull { it.indication != Indication.PASSED }
			?: concluded(Indication.PASSED)
	}

	/** A detailed report holding [blocks]. */
	fun report(vararg blocks: XmlBasicBuildingBlocks) = DetailedReport(
		XmlDetailedReport().apply { basicBuildingBlocks.addAll(blocks) }
	)

	/** Diagnostic data that holds the revocation tokens [revocationIds]. */
	fun diagnostics(vararg revocationIds: String) = DiagnosticData(
		XmlDiagnosticData().apply {
			revocationIds.forEach { revocationId -> usedRevocations.add(XmlRevocation().apply { id = revocationId }) }
		}
	)

	val detailedReport = report(
		signatureBlocks(
			"S-1",
			subXcv(
				"C-signer",
				listOf(
					rac("R-good", Indication.PASSED),
					rac("R-forged", Indication.FAILED),
					rac("R-unchecked-issuer", Indication.INDETERMINATE),
					rac("R-no-conclusion", null),
					rac("R-second-good", Indication.PASSED),
				),
			),
			subXcv("C-issuer", listOf(rac("R-issuer", Indication.PASSED))),
			subXcv("C-no-revocation-needed", null),
		)
	)

	test("only the tokens whose acceptance check passed for the certificate are accepted") {
		detailedReport.acceptedRevocationIds(noDiagnostics, "S-1", "C-signer") shouldBe
				setOf("R-good", "R-second-good")
	}

	test("tokens accepted for another certificate of the chain are not accepted for this one") {
		detailedReport.acceptedRevocationIds(noDiagnostics, "S-1", "C-issuer") shouldBe setOf("R-issuer")
	}

	test("a certificate whose revocation data was never selected has no accepted tokens") {
		detailedReport.acceptedRevocationIds(noDiagnostics, "S-1", "C-no-revocation-needed").shouldBeEmpty()
	}

	test("a certificate the token's validation did not reach has no accepted tokens") {
		detailedReport.acceptedRevocationIds(noDiagnostics, "S-1", "C-elsewhere").shouldBeEmpty()
	}

	test("a token without basic building blocks has no accepted tokens") {
		detailedReport.acceptedRevocationIds(noDiagnostics, "S-2", "C-signer").shouldBeEmpty()
	}

	test("basic building blocks without a certificate validation have no accepted tokens") {
		val withoutXcv = report(
			XmlBasicBuildingBlocks().apply {
				id = "S-1"
				type = Context.SIGNATURE
			}
		)

		withoutXcv.acceptedRevocationIds(noDiagnostics, "S-1", "C-signer").shouldBeEmpty()
	}

	context("a token DSS also validated on its own") {
		val signature = signatureBlocks(
			"S-1",
			subXcv(
				"C-signer",
				listOf(
					rac("R-sound", Indication.PASSED),
					rac("R-issuer-not-ca", Indication.PASSED),
					rac("R-weak-issuer-key", Indication.PASSED),
					rac("R-untrusted-and-weak", Indication.PASSED),
					rac("R-refused", Indication.FAILED),
				),
			),
		)
		val ownValidation = report(
			signature,
			revocationBlocks("R-sound", concluded(Indication.PASSED)),
			revocationBlocks(
				"R-issuer-not-ca",
				concluded(Indication.INDETERMINATE, SubIndication.CHAIN_CONSTRAINTS_FAILURE),
			),
			revocationBlocks(
				"R-weak-issuer-key",
				concluded(Indication.INDETERMINATE, SubIndication.CRYPTO_CONSTRAINTS_FAILURE_NO_POE),
			),
			revocationBlocks(
				"R-untrusted-and-weak",
				concluded(Indication.INDETERMINATE, SubIndication.NO_CERTIFICATE_CHAIN_FOUND),
				concluded(Indication.INDETERMINATE, SubIndication.CRYPTO_CONSTRAINTS_FAILURE_NO_POE),
			),
			revocationBlocks("R-refused", concluded(Indication.PASSED)),
		)
		val accepted = ownValidation.acceptedRevocationIds(
			diagnostics("R-sound", "R-issuer-not-ca", "R-weak-issuer-key", "R-untrusted-and-weak", "R-refused"),
			"S-1",
			"C-signer",
		)

		test("is accepted when its own validation passed") {
			("R-sound" in accepted) shouldBe true
		}

		test("is not accepted when its own validation failed, though its acceptance check passed") {
			("R-issuer-not-ca" in accepted) shouldBe false
		}

		test("is accepted when its own validation is indeterminate for a reason a proof of existence may resolve") {
			("R-weak-issuer-key" in accepted) shouldBe true
		}

		test("is judged by the first of its own checks that did not pass, as DSS's long-term validation judges it") {
			("R-untrusted-and-weak" in accepted) shouldBe false
		}

		test("is not accepted when its acceptance check failed, though its own validation passed") {
			("R-refused" in accepted) shouldBe false
		}

		test("is not accepted when the diagnostic data does not hold it") {
			ownValidation.acceptedRevocationIds(diagnostics("R-issuer-not-ca"), "S-1", "C-signer")
				.shouldBeEmpty()
		}
	}
})
