package cz.pizavo.omnisign.e2e

import org.bouncycastle.asn1.ASN1GeneralizedTime
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Issues throwaway leaf certificates and CRLs off the committed end-to-end CA, so that tests can put
 * a certificate's **dates** under their own control.
 *
 * The committed `signer.test.p12` and `tsa.test.p12` fixtures cover the happy path, but every
 * interesting preservation case turns on when a certificate expired relative to when a document was
 * timestamped and when revocation data was published. Those cases cannot be expressed by a fixture
 * frozen at the moment it was generated — and a committed certificate eventually expires on its own
 * and starts failing the suite for reasons no test intended. Minting from the CA key that is already
 * in the repository keeps the trust anchor stable while making the timeline a test parameter.
 *
 * @property caCert The committed CA certificate, which stays the trust anchor.
 * @property caKey Its private key, used to sign everything issued here.
 */
class TestPki(
	private val caCert: X509Certificate,
	private val caKey: PrivateKey,
) {

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
	 * Deliberately carries no CRL distribution point or AIA, matching the committed fixtures: the
	 * tests inject revocation data directly, so nothing here can reach the network.
	 *
	 * @param commonName The subject common name, which also becomes the PKCS#12 alias.
	 * @param notBefore Start of validity.
	 * @param notAfter End of validity — may be in the past, which is the point.
	 * @param timeStamping When true the certificate carries the critical time-stamping extended key
	 *   usage DSS requires of a TSA, instead of a signing key usage.
	 * @param password Password protecting the returned PKCS#12.
	 */
	fun issueLeaf(
		commonName: String,
		notBefore: Date,
		notAfter: Date,
		timeStamping: Boolean = false,
		password: String = "test1234",
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
			addExtension(Extension.basicConstraints, true, BasicConstraints(false))
			if (timeStamping) {
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
	 * Build a CA-signed CRL with no revoked entries.
	 *
	 * @param thisUpdate When the CRL claims to have been produced.
	 * @param nextUpdate When the issuer promises the next one.
	 * @param expiredCertsOnCRL When set, the CRL carries the `expiredCertsOnCRL` extension with this
	 *   date — the issuer's explicit statement that it reports revocation for certificates that
	 *   expired at or after it. Without it, DSS discards a CRL whose `thisUpdate` postdates the
	 *   certificate's expiry, because nothing says the issuer still speaks for that period.
	 */
	fun buildCrl(
		thisUpdate: Date,
		nextUpdate: Date,
		expiredCertsOnCRL: Date? = null,
	): X509CRL {
		val builder = X509v2CRLBuilder(JcaX509CertificateHolder(caCert).subject, thisUpdate).apply {
			setNextUpdate(nextUpdate)
			expiredCertsOnCRL?.let {
				addExtension(EXPIRED_CERTS_ON_CRL, false, ASN1GeneralizedTime(it))
			}
		}
		val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).setProvider(BC).build(caKey)
		return JcaX509CRLConverter().setProvider(BC).getCRL(builder.build(signer))
	}

	private companion object {
		const val BC = "BC"
		const val SIGNATURE_ALGORITHM = "SHA256withRSA"
		const val RSA_KEY_SIZE = 2048

		/**
		 * `expiredCertsOnCRL` (RFC 5280 / ETSI), referenced by OID rather than through a Bouncy Castle
		 * constant so the helper does not depend on which release exposes it under which name.
		 */
		val EXPIRED_CERTS_ON_CRL = ASN1ObjectIdentifier("2.5.29.60")
	}
}
