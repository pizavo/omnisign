package cz.pizavo.omnisign.e2e

import org.bouncycastle.asn1.ASN1GeneralizedTime
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.CRLReason
import org.bouncycastle.asn1.x509.DistributionPoint
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder
import org.bouncycastle.cert.ocsp.CertificateID
import org.bouncycastle.cert.ocsp.CertificateStatus
import org.bouncycastle.cert.ocsp.OCSPRespBuilder
import org.bouncycastle.cert.ocsp.RespID
import org.bouncycastle.cert.ocsp.RevokedStatus
import org.bouncycastle.cert.ocsp.UnknownStatus
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Issues throwaway leaf certificates, CRLs and OCSP responses off the committed end-to-end CA, so
 * that tests can put a certificate's **dates** and revocation status under their own control.
 *
 * The committed `signer.test.p12` and `tsa.test.p12` fixtures cover the happy path, but every
 * interesting preservation case turns on when a certificate expired relative to when a document was
 * timestamped and when revocation data was published. Those cases cannot be expressed by a fixture
 * frozen at the moment it was generated — and a committed certificate eventually expires on its own
 * and starts failing the suite for reasons no test intended. Minting from the CA key that is already
 * in the repository keeps the trust anchor stable while making the timeline a test parameter.
 *
 * Its builders name the BouncyCastle provider, which a new instance registers when no spec has yet,
 * so a spec that mints a certificate before it starts anything else also passes when run alone.
 *
 * @property caCert The committed CA certificate, which stays the trust anchor.
 * @property caKey Its private key, used to sign everything issued here.
 */
class TestPki(
	private val caCert: X509Certificate,
	private val caKey: PrivateKey,
) {

	init {
		if (Security.getProvider(BC) == null) Security.addProvider(BouncyCastleProvider())
	}

	/**
	 * A minted leaf: its certificate, its private key, and a PKCS#12 holding both with the CA
	 * certificate as the rest of the chain.
	 *
	 * @property certificate The issued certificate.
	 * @property privateKey Its private key.
	 * @property p12 PKCS#12 bytes, protected by the password passed to [issueLeaf].
	 */
	data class Leaf(
		val certificate: X509Certificate,
		val privateKey: PrivateKey,
		val p12: ByteArray,
	)

	/**
	 * Issue a leaf certificate valid from [notBefore] to [notAfter].
	 *
	 * By default the certificate names a CRL distribution point, as the committed fixtures do. DSS
	 * refuses to accept a timestamp or a revocation response signed under a certificate that names
	 * nowhere to check its revocation status, even when the status could be established by other
	 * means, so a leaf without one cannot serve as a TSA in any test about long-term material. The
	 * address is never dereferenced: the tests hand DSS the CA's CRL through an offline source, and
	 * nothing here can reach the network.
	 *
	 * @param commonName The subject common name, which also becomes the PKCS#12 alias.
	 * @param notBefore Start of validity.
	 * @param notAfter End of validity — may be in the past, which is the point.
	 * @param timeStamping When true the certificate carries the critical time-stamping extended key
	 *   usage DSS requires of a TSA, instead of a signing key usage.
	 * @param crlDistributionPoint The address the certificate names as its CRL distribution point, or
	 *   null for a certificate that names none — the shape DSS rejects as a timestamp or revocation
	 *   signer.
	 * @param password Password protecting the returned PKCS#12.
	 * @param certificateAuthority When true the certificate is an intermediate CA's instead, allowed
	 *   to sign certificates and CRLs, so that a [TestPki] made of it and its key issues under it.
	 */
	fun issueLeaf(
		commonName: String,
		notBefore: Date,
		notAfter: Date,
		timeStamping: Boolean = false,
		crlDistributionPoint: String? = CRL_DISTRIBUTION_POINT,
		password: String = "test1234",
		certificateAuthority: Boolean = false,
	): Leaf {
		val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }.generateKeyPair()
		val builder = JcaX509v3CertificateBuilder(
			JcaX509CertificateHolder(caCert).subject,
			BigInteger.valueOf(System.nanoTime()),
			notBefore,
			notAfter,
			X500Name("CN=$commonName"),
			keyPair.public,
		).apply {
			addExtension(Extension.basicConstraints, true, BasicConstraints(certificateAuthority))
			if (certificateAuthority) {
				addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
			} else if (timeStamping) {
				addExtension(
					Extension.extendedKeyUsage,
					true,
					ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping),
				)
				addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
			} else {
				addExtension(
					Extension.keyUsage,
					true,
					KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation),
				)
			}
			crlDistributionPoint?.let {
				val fullName = GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, it))
				addExtension(
					Extension.cRLDistributionPoints,
					false,
					CRLDistPoint(arrayOf(DistributionPoint(DistributionPointName(fullName), null, null))),
				)
			}
		}
		val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC).build(caKey)
		val certificate = JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer))

		val keyStore = KeyStore.getInstance("PKCS12").apply {
			load(null, null)
			setKeyEntry(commonName, keyPair.private, password.toCharArray(), arrayOf(certificate, caCert))
		}
		val out = ByteArrayOutputStream()
		keyStore.store(out, password.toCharArray())
		return Leaf(certificate, keyPair.private, out.toByteArray())
	}

	/**
	 * Build a CA-signed CRL, listing the certificates in [revoked] and no others.
	 *
	 * @param thisUpdate When the CRL claims to have been produced.
	 * @param nextUpdate When the issuer promises the next one.
	 * @param expiredCertsOnCRL When set, the CRL carries the `expiredCertsOnCRL` extension with this
	 *   date — the issuer's explicit statement that it reports revocation for certificates that
	 *   expired at or after it. Without it, DSS discards a CRL whose `thisUpdate` postdates the
	 *   certificate's expiry, because nothing says the issuer still speaks for that period.
	 * @param revoked The serial numbers of the certificates the CRL lists, each with the date it
	 *   states the certificate was revoked on.
	 * @param reason The RFC 5280 reason code every entry carries, such as [CRLReason.certificateHold]
	 *   for a suspension, or 0 for entries that carry none, as BouncyCastle then leaves the reason
	 *   code out.
	 */
	fun buildCrl(
		thisUpdate: Date,
		nextUpdate: Date,
		expiredCertsOnCRL: Date? = null,
		revoked: Map<BigInteger, Date> = emptyMap(),
		reason: Int = CRLReason.keyCompromise,
	): X509CRL {
		val builder = X509v2CRLBuilder(JcaX509CertificateHolder(caCert).subject, thisUpdate).apply {
			setNextUpdate(nextUpdate)
			expiredCertsOnCRL?.let {
				addExtension(EXPIRED_CERTS_ON_CRL, false, ASN1GeneralizedTime(it))
			}
			revoked.forEach { (serialNumber, date) -> addCRLEntry(serialNumber, date, reason) }
		}
		val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC).build(caKey)
		return JcaX509CRLConverter().setProvider(BC).getCRL(builder.build(signer))
	}

	/**
	 * Build a successful OCSP response, signed by the CA itself as the responder, that states
	 * [status] for [certificate].
	 *
	 * @param certificate A certificate the CA issued.
	 * @param status The status the response states: [CertificateStatus.GOOD], a [RevokedStatus] or
	 *   an [UnknownStatus].
	 * @param thisUpdate When the response claims its status was known to be correct.
	 * @param nextUpdate When newer status information will be available.
	 * @return The DER encoding of the response, as an OCSP responder would send it.
	 */
	fun buildOcspResponse(
		certificate: X509Certificate,
		status: CertificateStatus?,
		thisUpdate: Date,
		nextUpdate: Date,
	): ByteArray {
		val caHolder = JcaX509CertificateHolder(caCert)
		val digestCalculator = JcaDigestCalculatorProviderBuilder().setProvider(BC).build().get(CertificateID.HASH_SHA1)
		val basic = BasicOCSPRespBuilder(RespID(caHolder.subject))
			.addResponse(CertificateID(digestCalculator, caHolder, certificate.serialNumber), status, thisUpdate, nextUpdate)
			.build(JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC).build(caKey), arrayOf(caHolder), thisUpdate)
		return OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basic).encoded
	}

	private companion object {
		const val BC = "BC"
		const val SIGNATURE_ALGORITHM = "SHA256withRSA"
		const val RSA_KEY_SIZE = 4096

		/**
		 * The CRL distribution point a leaf names unless told otherwise — the same address the
		 * committed fixtures carry. It lies under `.invalid`, which RFC 6761 reserves for names that
		 * must never resolve, so even a verifier wired to an online CRL source could not reach it.
		 */
		const val CRL_DISTRIBUTION_POINT = "http://crl.omnisign.invalid/test-root-ca.crl"

		/**
		 * `expiredCertsOnCRL` (RFC 5280 / ETSI), referenced by OID rather than through a Bouncy Castle
		 * constant so the helper does not depend on which release exposes it under which name.
		 */
		val EXPIRED_CERTS_ON_CRL = ASN1ObjectIdentifier("2.5.29.60")
	}
}
