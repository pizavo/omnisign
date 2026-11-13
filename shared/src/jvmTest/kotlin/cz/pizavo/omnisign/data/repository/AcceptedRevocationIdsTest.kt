package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.detailedreport.DetailedReport
import eu.europa.esig.dss.detailedreport.jaxb.XmlBasicBuildingBlocks
import eu.europa.esig.dss.detailedreport.jaxb.XmlCRS
import eu.europa.esig.dss.detailedreport.jaxb.XmlConclusion
import eu.europa.esig.dss.detailedreport.jaxb.XmlDetailedReport
import eu.europa.esig.dss.detailedreport.jaxb.XmlRAC
import eu.europa.esig.dss.detailedreport.jaxb.XmlSubXCV
import eu.europa.esig.dss.detailedreport.jaxb.XmlXCV
import eu.europa.esig.dss.enumerations.Context
import eu.europa.esig.dss.enumerations.Indication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Verifies that only the revocation tokens whose acceptance check passed for the requested
 * certificate, in the requested token's basic building blocks, are reported as accepted.
 */
class AcceptedRevocationIdsTest : FunSpec({

	/** A revocation acceptance check of the token [id] that ended with [indication]. */
	fun rac(id: String, indication: Indication?) = XmlRAC().apply {
		this.id = id
		conclusion = indication?.let { XmlConclusion().apply { this.indication = it } }
	}

	/** The certificate validation of [certificateId], selecting among [racs]; no selector when null. */
	fun subXcv(certificateId: String, racs: List<XmlRAC>?) = XmlSubXCV().apply {
		id = certificateId
		crs = racs?.let { XmlCRS().apply { id = certificateId; rac.addAll(it) } }
	}

	/** A detailed report whose only basic building blocks belong to [tokenId] and validate [subXcvs]. */
	fun report(tokenId: String, vararg subXcvs: XmlSubXCV) = DetailedReport(
		XmlDetailedReport().apply {
			basicBuildingBlocks += XmlBasicBuildingBlocks().apply {
				id = tokenId
				type = Context.SIGNATURE
				xcv = XmlXCV().apply { subXCV.addAll(subXcvs) }
			}
		}
	)

	val detailedReport = report(
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

	test("only the tokens whose acceptance check passed for the certificate are accepted") {
		detailedReport.acceptedRevocationIds("S-1", "C-signer") shouldBe setOf("R-good", "R-second-good")
	}

	test("tokens accepted for another certificate of the chain are not accepted for this one") {
		detailedReport.acceptedRevocationIds("S-1", "C-issuer") shouldBe setOf("R-issuer")
	}

	test("a certificate whose revocation data was never selected has no accepted tokens") {
		detailedReport.acceptedRevocationIds("S-1", "C-no-revocation-needed").shouldBeEmpty()
	}

	test("a certificate the token's validation did not reach has no accepted tokens") {
		detailedReport.acceptedRevocationIds("S-1", "C-elsewhere").shouldBeEmpty()
	}

	test("a token without basic building blocks has no accepted tokens") {
		detailedReport.acceptedRevocationIds("S-2", "C-signer").shouldBeEmpty()
	}

	test("basic building blocks without a certificate validation have no accepted tokens") {
		val withoutXcv = DetailedReport(
			XmlDetailedReport().apply {
				basicBuildingBlocks += XmlBasicBuildingBlocks().apply {
					id = "S-1"
					type = Context.SIGNATURE
				}
			}
		)

		withoutXcv.acceptedRevocationIds("S-1", "C-signer").shouldBeEmpty()
	}
})
