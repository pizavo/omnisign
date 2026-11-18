package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import eu.europa.esig.dss.diagnostic.CertificateWrapper
import eu.europa.esig.dss.diagnostic.jaxb.XmlCertificate
import eu.europa.esig.dss.diagnostic.jaxb.XmlTrusted
import eu.europa.esig.dss.enumerations.CertificateSourceType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies the per-reference trust-policy downgrade: which trust anchor a chain ends at, and whether
 * its type demotes a component.
 *
 * Only a store-managed anchor typed for the other role triggers a downgrade, while trust a trusted
 * list or an unmanaged anchor gives never does, and [TrustedCertificateType.ANY] satisfies both
 * roles. The anchor is the first trusted certificate in the chain, signing certificate first, as DSS
 * ends its validation there: trusted certificates above it, which DSS lists up to the self-signed
 * root, take no part.
 */
class TrustPolicyDowngradeTest : FunSpec({

	val ca = TrustedCertificateType.CA
	val tsa = TrustedCertificateType.TSA
	val any = TrustedCertificateType.ANY

	/** A chain certificate with the DSS id [id], trusted when [trusted], that [sources] hold. */
	fun cert(id: String, trusted: Boolean, vararg sources: CertificateSourceType): CertificateWrapper =
		CertificateWrapper(
			XmlCertificate().apply {
				setId(id)
				setTrusted(XmlTrusted().apply { isValue = trusted })
				setSources(sources.toList())
			},
		)

	val signer = cert("C-SIGNER", trusted = false, CertificateSourceType.SIGNATURE)

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

	test("a chain without a trusted certificate has no managed anchor, however the store types its certificates") {
		val issuer = cert("C-ISSUER", trusted = false, CertificateSourceType.SIGNATURE)
		managedAnchorType(listOf(signer, issuer), mapOf("C-ISSUER" to tsa)).shouldBeNull()
		managedAnchorType(emptyList(), mapOf("C-ISSUER" to tsa)).shouldBeNull()
	}

	test("the first trusted certificate is the anchor, and a store type above it does not count") {
		val issuing = cert("C-ISSUING", trusted = true, CertificateSourceType.TRUSTED_STORE)
		val root = cert("C-ROOT", trusted = true, CertificateSourceType.TRUSTED_STORE)
		val chain = listOf(signer, issuing, root)

		managedAnchorType(chain, mapOf("C-ISSUING" to tsa, "C-ROOT" to ca)) shouldBe tsa
		managedAnchorType(chain, mapOf("C-ISSUING" to ca, "C-ROOT" to tsa)) shouldBe ca
	}

	test("an anchor the store does not manage leaves the trust undecided, even under a store-typed root") {
		val issuing = cert("C-ISSUING", trusted = true, CertificateSourceType.TRUSTED_STORE)
		val root = cert("C-ROOT", trusted = true, CertificateSourceType.TRUSTED_STORE)

		managedAnchorType(listOf(signer, issuing, root), mapOf("C-ROOT" to tsa)).shouldBeNull()
	}

	test("an anchor a trusted list trusts too is never downgraded, whatever the store types it") {
		val listed = cert("C-LISTED", trusted = true, CertificateSourceType.TRUSTED_STORE, CertificateSourceType.TRUSTED_LIST)
		val root = cert("C-ROOT", trusted = true, CertificateSourceType.TRUSTED_STORE)

		managedAnchorType(listOf(signer, listed, root), mapOf("C-LISTED" to tsa, "C-ROOT" to tsa)).shouldBeNull()
	}

	test("a signing certificate the store trusts itself is its own anchor") {
		val pinned = cert("C-PINNED", trusted = true, CertificateSourceType.TRUSTED_STORE, CertificateSourceType.SIGNATURE)
		val root = cert("C-ROOT", trusted = true, CertificateSourceType.TRUSTED_STORE)

		managedAnchorType(listOf(pinned, root), mapOf("C-PINNED" to tsa, "C-ROOT" to ca)) shouldBe tsa
	}
})
