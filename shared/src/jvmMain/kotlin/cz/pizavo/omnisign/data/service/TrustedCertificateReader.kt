package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Reads an X.509 certificate from a PEM or DER file and produces a
 * [TrustedCertificateConfig] with the DER bytes stored as Base64.
 *
 * This is intentionally a stateless object, so it can be used from any JVM
 * platform (CLI, desktop, server) without DI wiring.
 */
object TrustedCertificateReader {

	/**
	 * Parse [certFile] as an X.509 certificate and return a [TrustedCertificateConfig]
	 * containing the Base64-encoded DER bytes and the extracted subject DN.
	 *
	 * @param name Human-readable label for this certificate.
	 * @param certFile PEM or DER certificate file.
	 * @param type Trust type (ANY, CA, or TSA).
	 * @throws java.security.cert.CertificateException if the file cannot be parsed.
	 * @throws java.io.FileNotFoundException if the file does not exist.
	 */
	fun read(name: String, certFile: File, type: TrustedCertificateType): TrustedCertificateConfig {
		val x509 = certFile.inputStream().use { stream ->
			CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
		}
		return TrustedCertificateConfig(
			name = name,
			type = type,
			certificateBase64 = Base64.getEncoder().encodeToString(x509.encoded),
			subjectDN = x509.subjectX500Principal.name,
		)
	}
}
