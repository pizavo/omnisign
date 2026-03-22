package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

/**
 * Verifies [TrustedCertificateReader] correctly parses DER and PEM certificate
 * files and produces [cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig]
 * instances with Base64-encoded DER bytes and extracted subject DN.
 */
class TrustedCertificateReaderTest : FunSpec({
	
	fun generateSelfSignedCert(cn: String = "CN=Test CA"): X509Certificate {
		val keyPairGen = KeyPairGenerator.getInstance("RSA")
		keyPairGen.initialize(2048)
		val keyPair = keyPairGen.generateKeyPair()
		
		val subject = X500Name(cn)
		val serial = BigInteger.valueOf(System.currentTimeMillis())
		val now = Clock.System.now()
		val notBefore = Date.from(now.toJavaInstant())
		val notAfter = Date.from((now + 365.days).toJavaInstant())
		
		val builder = JcaX509v3CertificateBuilder(
			subject, serial, notBefore, notAfter, subject, keyPair.public
		)
		val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
		return JcaX509CertificateConverter().getCertificate(builder.build(signer))
	}
	
	fun writeDerFile(cert: X509Certificate): File =
		File.createTempFile("cert-", ".der").apply {
			deleteOnExit()
			writeBytes(cert.encoded)
		}
	
	fun writePemFile(cert: X509Certificate): File {
		val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
			.encodeToString(cert.encoded)
		return File.createTempFile("cert-", ".pem").apply {
			deleteOnExit()
			writeText("-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n")
		}
	}
	
	test("read parses DER certificate file") {
		val cert = generateSelfSignedCert()
		val file = writeDerFile(cert)
		
		val config = TrustedCertificateReader.read("my-ca", file, TrustedCertificateType.CA)
		
		config.name shouldBe "my-ca"
		config.type shouldBe TrustedCertificateType.CA
		config.certificateBase64.shouldNotBeBlank()
		config.subjectDN shouldContain "Test CA"
	}
	
	test("read parses PEM certificate file") {
		val cert = generateSelfSignedCert("CN=PEM Test")
		val file = writePemFile(cert)
		
		val config = TrustedCertificateReader.read("pem-cert", file, TrustedCertificateType.TSA)
		
		config.name shouldBe "pem-cert"
		config.type shouldBe TrustedCertificateType.TSA
		config.certificateBase64.shouldNotBeBlank()
		config.subjectDN shouldContain "PEM Test"
	}
	
	test("read produces Base64 that round-trips to the original DER") {
		val cert = generateSelfSignedCert()
		val file = writeDerFile(cert)
		
		val config = TrustedCertificateReader.read("roundtrip", file, TrustedCertificateType.CA)
		val decoded = Base64.getDecoder().decode(config.certificateBase64)
		
		decoded shouldBe cert.encoded
	}
	
	test("read throws for non-certificate file") {
		val garbage = File.createTempFile("bad-", ".der").apply {
			deleteOnExit()
			writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
		}
		
		shouldThrow<CertificateException> {
			TrustedCertificateReader.read("bad", garbage, TrustedCertificateType.CA)
		}
	}
	
	test("read preserves TSA type") {
		val cert = generateSelfSignedCert("CN=TSA Signer")
		val file = writeDerFile(cert)
		
		val config = TrustedCertificateReader.read("tsa", file, TrustedCertificateType.TSA)
		
		config.type shouldBe TrustedCertificateType.TSA
	}
	
	test("read preserves ANY type") {
		val cert = generateSelfSignedCert("CN=Root CA")
		val file = writeDerFile(cert)
		
		val config = TrustedCertificateReader.read("root", file, TrustedCertificateType.ANY)
		
		config.type shouldBe TrustedCertificateType.ANY
	}
})

