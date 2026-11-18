package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import eu.europa.esig.dss.detailedreport.jaxb.XmlSubXCV
import eu.europa.esig.dss.detailedreport.jaxb.XmlXCV
import eu.europa.esig.dss.diagnostic.CertificateWrapper
import eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate
import eu.europa.esig.dss.enumerations.CertificateSourceType
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.spi.x509.CertificateSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Date

/**
 * Verifies the per-reference trust-policy downgrade: which trust anchor DSS validated a chain
 * against, whether a trusted list trusts that anchor, and whether its type demotes a component.
 *
 * Only a store-managed anchor typed for the other role triggers a downgrade, while trust a trusted
 * list gives at the validation time or an unmanaged anchor gives never does, and
 * [TrustedCertificateType.ANY] satisfies both roles. The anchor is the last trusted certificate
 * DSS's X.509 certificate validation checked, since DSS stops there: trusted certificates above it,
 * which DSS lists up to the self-signed root, take no part, and neither does a trusted certificate
 * DSS passed over on its way.
 */
class TrustPolicyDowngradeTest : FunSpec({

	val ca = TrustedCertificateType.CA
	val tsa = TrustedCertificateType.TSA
	val any = TrustedCertificateType.ANY

	/** A chain certificate with the DSS id [id]. */
	fun cert(id: String): CertificateWrapper = CertificateWrapper(XmlCertificate().apply { setId(id) })

	/**
	 * DSS's X.509 certificate validation of a chain that checked the [checked] certificates in turn,
	 * each given as its DSS id and whether DSS trusts it.
	 */
	fun validation(vararg checked: Pair<String, Boolean>): XmlXCV = XmlXCV().apply {
		subXCV.addAll(
			checked.map { (id, trusted) ->
				XmlSubXCV().apply {
					this.id = id
					isTrustAnchor = trusted
				}
			},
		)
	}

	val noList: (CertificateWrapper) -> Boolean = { false }
	val chain = listOf(cert("C-SIGNER"), cert("C-ISSUING"), cert("C-ROOT"))
	val stoppedAtIssuing = validation("C-SIGNER" to false, "C-ISSUING" to true)

	test("a component the store does not decide is never downgraded") {
		isDowngradedByPolicy(null, ca) shouldBe false
		isDowngradedByPolicy(null, tsa) shouldBe false
	}

	test("a signature is downgraded when its anchor is TSA-only, and kept when it grants CA or ANY") {
		isDowngradedByPolicy(tsa, ca) shouldBe true
		isDowngradedByPolicy(ca, ca) shouldBe false
		isDowngradedByPolicy(any, ca) shouldBe false
	}

	test("a timestamp is downgraded when its anchor is CA-only, and kept when it grants TSA or ANY") {
		isDowngradedByPolicy(ca, tsa) shouldBe true
		isDowngradedByPolicy(tsa, tsa) shouldBe false
		isDowngradedByPolicy(any, tsa) shouldBe false
	}

	test("a chain DSS ran no X.509 validation for, or one that reached no trusted certificate, has no managed anchor") {
		val types = mapOf("C-ISSUING" to tsa, "C-ROOT" to tsa)

		managedAnchorType(chain, null, types, noList).shouldBeNull()
		managedAnchorType(chain, validation("C-SIGNER" to false, "C-ISSUING" to false, "C-ROOT" to false), types, noList)
			.shouldBeNull()
	}

	test("the anchor is the trusted certificate DSS stopped at, and a store type above it does not count") {
		managedAnchorType(chain, stoppedAtIssuing, mapOf("C-ISSUING" to tsa, "C-ROOT" to ca), noList) shouldBe tsa
		managedAnchorType(chain, stoppedAtIssuing, mapOf("C-ISSUING" to ca, "C-ROOT" to tsa), noList) shouldBe ca
	}

	test("a trusted certificate DSS passed over is not the anchor, so the type of the one it stopped at counts") {
		val passedIssuing = validation("C-SIGNER" to false, "C-ISSUING" to true, "C-ROOT" to true)

		managedAnchorType(chain, passedIssuing, mapOf("C-ISSUING" to ca, "C-ROOT" to tsa), noList) shouldBe tsa
		managedAnchorType(chain, passedIssuing, mapOf("C-ISSUING" to tsa, "C-ROOT" to ca), noList) shouldBe ca
	}

	test("an anchor the store does not manage leaves the trust undecided, even under a store-typed root") {
		managedAnchorType(chain, stoppedAtIssuing, mapOf("C-ROOT" to tsa), noList).shouldBeNull()
	}

	test("a store anchor a trusted list trusts at the validation time is never downgraded, whatever the store types it") {
		val types = mapOf("C-ISSUING" to tsa, "C-ROOT" to tsa)

		managedAnchorType(chain, stoppedAtIssuing, types) { it.id == "C-ISSUING" }.shouldBeNull()
		managedAnchorType(chain, stoppedAtIssuing, types) { it.id == "C-ROOT" } shouldBe tsa
	}

	test("a signing certificate the store trusts itself is its own anchor") {
		managedAnchorType(chain, validation("C-SIGNER" to true), mapOf("C-SIGNER" to tsa, "C-ROOT" to ca), noList) shouldBe tsa
	}

	val token = mockk<CertificateToken>()
	val validationTime = Date(1_700_000_000_000)

	/** A trusted certificate source of [type] that trusts [token] at [validationTime] when [trusts]. */
	fun source(type: CertificateSourceType, trusts: Boolean): CertificateSource = mockk {
		every { certificateSourceType } returns type
		every { isTrustedAtTime(token, validationTime) } returns trusts
	}

	test("a trusted list counts only when it trusts the certificate at the validation time") {
		isTrustedByListAt(listOf(source(CertificateSourceType.TRUSTED_LIST, trusts = true)), token, validationTime) shouldBe true
		isTrustedByListAt(listOf(source(CertificateSourceType.TRUSTED_LIST, trusts = false)), token, validationTime) shouldBe false
	}

	test("a source other than a trusted list never counts, though it trusts the certificate") {
		isTrustedByListAt(listOf(source(CertificateSourceType.TRUSTED_STORE, trusts = true)), token, validationTime) shouldBe false
		isTrustedByListAt(emptyList(), token, validationTime) shouldBe false
	}

	test("one trusted list that trusts the certificate is enough, among sources that do not") {
		val sources = listOf(
			source(CertificateSourceType.TRUSTED_STORE, trusts = true),
			source(CertificateSourceType.TRUSTED_LIST, trusts = false),
			source(CertificateSourceType.TRUSTED_LIST, trusts = true),
		)

		isTrustedByListAt(sources, token, validationTime) shouldBe true
	}
})
