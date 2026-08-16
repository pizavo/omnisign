package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.value.CertificateTrustTier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Verifies the three ordering keys of [signingCertificateOrder] and the tier collapsing
 * that backs its first key.
 */
class SigningCertificateOrderTest : FunSpec({

	fun cert(
		name: String,
		validTo: String = "2030-01-01T00:00:00Z",
		isQualified: Boolean? = null,
		isQscd: Boolean? = null,
	) = AvailableCertificateInfo(
		alias = "$name-alias",
		subjectDN = "CN=$name,C=CZ",
		issuerDN = "CN=Issuer,C=CZ",
		validFrom = Instant.parse("2020-01-01T00:00:00Z"),
		validTo = Instant.parse(validTo),
		tokenType = "PKCS11",
		isQualified = isQualified,
		isQscd = isQscd,
	)

	fun names(vararg certs: AvailableCertificateInfo) =
		certs.toList().sortedWith(signingCertificateOrder).map { it.alias.removeSuffix("-alias") }

	test("qualification outranks every other key") {
		val qscd = cert("zzz-qscd", validTo = "2027-01-01T00:00:00Z", isQualified = true, isQscd = true)
		val qualified = cert("aaa-qualified", validTo = "2029-01-01T00:00:00Z", isQualified = true)
		val plain = cert("bbb-plain", validTo = "2031-01-01T00:00:00Z", isQualified = false)

		names(plain, qualified, qscd) shouldBe listOf("zzz-qscd", "aaa-qualified", "bbb-plain")
	}

	test("QcSSCD wins even when QcCompliance is absent") {
		CertificateTrustTier.of(isQualified = null, isQscd = true) shouldBe CertificateTrustTier.QUALIFIED_QSCD
		CertificateTrustTier.of(isQualified = false, isQscd = true) shouldBe CertificateTrustTier.QUALIFIED_QSCD
	}

	test("a certificate without QCStatements is UNKNOWN, not NOT_QUALIFIED") {
		CertificateTrustTier.of(isQualified = null, isQscd = null) shouldBe CertificateTrustTier.UNKNOWN
		CertificateTrustTier.of(isQualified = false, isQscd = null) shouldBe CertificateTrustTier.NOT_QUALIFIED
	}

	test("NOT_QUALIFIED and UNKNOWN tie, so expiry decides between them") {
		val absent = cert("absent", validTo = "2032-01-01T00:00:00Z")
		val asserted = cert("asserted", validTo = "2028-01-01T00:00:00Z", isQualified = false)

		names(asserted, absent) shouldBe listOf("absent", "asserted")
	}

	test("within a tier the later expiry comes first") {
		val renewed = cert("renewed", validTo = "2029-01-01T00:00:00Z", isQualified = true, isQscd = true)
		val expiring = cert("expiring", validTo = "2026-06-26T00:00:00Z", isQualified = true, isQscd = true)

		names(expiring, renewed) shouldBe listOf("renewed", "expiring")
	}

	test("equal tier and expiry fall through to the displayed common name") {
		val beta = cert("Beta", isQualified = true)
		val alpha = cert("Alpha", isQualified = true)

		names(beta, alpha) shouldBe listOf("Alpha", "Beta")
	}

	test("a subject without a CN falls back to the full DN for the name key") {
		val noCn = AvailableCertificateInfo(
			alias = "no-cn-alias",
			subjectDN = "O=Acme,C=CZ",
			issuerDN = "CN=Issuer,C=CZ",
			validFrom = Instant.parse("2020-01-01T00:00:00Z"),
			validTo = Instant.parse("2030-01-01T00:00:00Z"),
			tokenType = "PKCS11",
		)
		val zed = cert("Zed")

		names(zed, noCn) shouldBe listOf("no-cn", "Zed")
	}
})
