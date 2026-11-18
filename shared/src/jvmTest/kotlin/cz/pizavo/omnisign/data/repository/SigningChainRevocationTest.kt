package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.e2e.TestPki
import eu.europa.esig.dss.enumerations.CertificateStatus
import eu.europa.esig.dss.enumerations.RevocationReason
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.pades.PAdESSignatureParameters
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.revocation.crl.ExternalResourcesCRLSource
import eu.europa.esig.dss.spi.x509.revocation.ocsp.ExternalResourcesOCSPSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bouncycastle.asn1.x509.CRLReason
import org.bouncycastle.cert.ocsp.RevokedStatus
import org.bouncycastle.cert.ocsp.UnknownStatus
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Verifies [signingChainRevocation] against real DSS, with the committed end-to-end CA as the trust
 * anchor and CRLs and OCSP responses minted by [TestPki], handed to DSS through offline sources so
 * that nothing reaches the network.
 */
class SigningChainRevocationTest : FunSpec({

	val pass = "test1234"
	val day = 86_400_000L
	val now = System.currentTimeMillis()

	val caKeyStore = KeyStore.getInstance("PKCS12").apply {
		SigningChainRevocationTest::class.java.getResourceAsStream("/e2e/ca.test.p12")!!.use { load(it, pass.toCharArray()) }
	}
	val caAlias = caKeyStore.aliases().nextElement()
	val caCert = caKeyStore.getCertificate(caAlias) as X509Certificate
	val ca = TestPki(caCert, caKeyStore.getKey(caAlias, pass.toCharArray()) as PrivateKey)

	val signer = ca.issueLeaf("OmniSign Test Revoked Signer", Date(now - 3 * day), Date(now + 365 * day)).certificate
	val intermediate = ca.issueLeaf(
		"OmniSign Test Intermediate CA", Date(now - 3 * day), Date(now + 365 * day), certificateAuthority = true,
	)
	val underIntermediate = TestPki(intermediate.certificate, intermediate.privateKey)
		.issueLeaf("OmniSign Test Signer Under Intermediate", Date(now - 3 * day), Date(now + 365 * day))
		.certificate

	/** A CRL from [issuer], produced an hour ago, that lists [revoked], each revoked a day ago. */
	fun crl(
		issuer: TestPki = ca,
		vararg revoked: X509Certificate,
		reason: Int = CRLReason.keyCompromise,
		revokedOn: Date = Date(now - day),
	): X509CRL = issuer.buildCrl(
		thisUpdate = Date(now - 3_600_000L),
		nextUpdate = Date(now + 30 * day),
		revoked = revoked.associate { it.serialNumber to revokedOn },
		reason = reason,
	)

	/**
	 * A verifier configured as OmniSign's signing verifier is, trusting the committed CA unless
	 * [trusted] is false, and fetching revocation data from [crls] and [ocspResponses] only.
	 */
	fun verifier(
		crls: List<X509CRL> = emptyList(),
		ocspResponses: List<ByteArray> = emptyList(),
		trusted: Boolean = true,
	): CommonCertificateVerifier = CommonCertificateVerifier().apply {
		if (trusted) {
			setTrustedCertSources(CommonTrustedCertificateSource().apply { addCertificate(CertificateToken(caCert)) })
		}
		if (crls.isNotEmpty()) {
			crlSource = ExternalResourcesCRLSource(*crls.map { InMemoryDocument(it.encoded) }.toTypedArray())
		}
		if (ocspResponses.isNotEmpty()) {
			ocspSource = ExternalResourcesOCSPSource(*ocspResponses.map { InMemoryDocument(it) }.toTypedArray())
		}
		isCheckRevocationForUntrustedChains = false
		alertOnMissingRevocationData = null
		alertOnNoRevocationAfterBestSignatureTime = null
	}

	/** Parameters that sign with [certificate] under [chain], at [signingDate]. */
	fun parameters(
		certificate: X509Certificate,
		vararg chain: X509Certificate,
		signingDate: Date = Date(),
	): PAdESSignatureParameters = PAdESSignatureParameters().apply {
		setSigningCertificate(CertificateToken(certificate))
		certificateChain = (listOf(certificate) + chain).map { CertificateToken(it) }
		bLevel().signingDate = signingDate
	}

	test("a signing certificate its issuer's CRL lists is reported, with the entry's date and reason") {
		val revocation = signingChainRevocation(parameters(signer, caCert), verifier(listOf(crl(ca, signer))))
			.shouldNotBeNull()

		revocation.relatedCertificate shouldBe CertificateToken(signer)
		revocation.status shouldBe CertificateStatus.REVOKED
		revocation.reason shouldBe RevocationReason.KEY_COMPROMISE
		revocation.revocationDate.time shouldBe (now - day) / 1000 * 1000
	}

	test("a suspended signing certificate is reported as well") {
		val revocation = signingChainRevocation(
			parameters(signer, caCert),
			verifier(listOf(crl(ca, signer, reason = CRLReason.certificateHold))),
		).shouldNotBeNull()

		revocation.relatedCertificate shouldBe CertificateToken(signer)
		revocation.reason shouldBe RevocationReason.CERTIFICATE_HOLD
	}

	test("a signing certificate is reported when it is given without its chain") {
		signingChainRevocation(parameters(signer), verifier(listOf(crl(ca, signer)))).shouldNotBeNull()
			.relatedCertificate shouldBe CertificateToken(signer)
	}

	test("a signing certificate is reported when the chain lists only its issuer") {
		val parameters = PAdESSignatureParameters().apply {
			setSigningCertificate(CertificateToken(signer))
			certificateChain = listOf(CertificateToken(caCert))
		}
		signingChainRevocation(parameters, verifier(listOf(crl(ca, signer)))).shouldNotBeNull()
			.relatedCertificate shouldBe CertificateToken(signer)
	}

	test("a revoked intermediate CA is reported, not the signing certificate it issued") {
		val intermediateCa = TestPki(intermediate.certificate, intermediate.privateKey)
		val revocation = signingChainRevocation(
			parameters(underIntermediate, intermediate.certificate, caCert),
			verifier(listOf(crl(ca, intermediate.certificate), crl(intermediateCa))),
		).shouldNotBeNull()

		revocation.relatedCertificate shouldBe CertificateToken(intermediate.certificate)
	}

	test("a certificate its issuer's CRL does not list is not reported") {
		signingChainRevocation(parameters(signer, caCert), verifier(listOf(crl(ca)))).shouldBeNull()
	}

	test("a certificate revoked after the signing time is not reported") {
		signingChainRevocation(
			parameters(signer, caCert, signingDate = Date(now - 2 * day)),
			verifier(listOf(crl(ca, signer))),
		).shouldBeNull()
	}

	test("a revoked certificate is not reported when no revocation data can be obtained") {
		signingChainRevocation(parameters(signer, caCert), verifier()).shouldBeNull()
	}

	test("a revoked certificate under a chain that leads to no trusted certificate is not reported") {
		signingChainRevocation(parameters(signer, caCert), verifier(listOf(crl(ca, signer)), trusted = false))
			.shouldBeNull()
	}

	test("an OCSP response that says revoked is reported") {
		val response = ca.buildOcspResponse(
			signer, RevokedStatus(Date(now - day), CRLReason.keyCompromise), Date(now - 3_600_000L), Date(now + day),
		)
		signingChainRevocation(parameters(signer, caCert), verifier(ocspResponses = listOf(response))).shouldNotBeNull()
			.relatedCertificate shouldBe CertificateToken(signer)
	}

	test("an OCSP response that says unknown is not reported, as it states no revocation") {
		val response = ca.buildOcspResponse(signer, UnknownStatus(), Date(now - 3_600_000L), Date(now + day))
		signingChainRevocation(parameters(signer, caCert), verifier(ocspResponses = listOf(response))).shouldBeNull()
	}
})
