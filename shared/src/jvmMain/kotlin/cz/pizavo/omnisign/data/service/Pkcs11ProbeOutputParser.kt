package cz.pizavo.omnisign.data.service

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Field count of a worker `CERT` line: `CERT`, slot, CKA_ID hex, label b64, DER b64.
 */
private const val CERT_LINE_FIELDS = 5

/**
 * Parse the `CERT\t<slot>\t<ckaIdHex>\t<labelBase64>\t<derBase64>` lines emitted by a
 * `--certs` [Pkcs11ProbeWorker] run into [Pkcs11NoLoginParsedCert]s.
 *
 * Non-`CERT` lines (token-identity output, stray logging) are ignored, and any line that
 * fails to split into five fields or whose Base64/DER does not parse as an X.509
 * certificate is dropped — the parser never throws.  Single source of truth for the
 * `CERT` line format, shared by the diagnostics report and no-login discovery.
 */
internal fun parseProbeNoLoginCerts(stdout: String): List<Pkcs11NoLoginParsedCert> {
	val factory = runCatching { CertificateFactory.getInstance("X.509") }.getOrNull() ?: return emptyList()
	return stdout.lines()
		.filter { it.startsWith("CERT\t") }
		.mapNotNull { line ->
			val parts = line.split('\t')
			if (parts.size != CERT_LINE_FIELDS) return@mapNotNull null
			val der = runCatching { Base64.getDecoder().decode(parts[4]) }.getOrNull() ?: return@mapNotNull null
			val cert = runCatching {
				factory.generateCertificate(der.inputStream()) as X509Certificate
			}.getOrNull() ?: return@mapNotNull null
			Pkcs11NoLoginParsedCert(
				certificate = cert,
				ckaIdHex = parts[2],
				label = runCatching { String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8) }
					.getOrDefault(""),
				slotId = parts[1].toLongOrNull() ?: 0L,
			)
		}
}

/**
 * Project [parseProbeNoLoginCerts] into the diagnostics-report shape.
 */
internal fun parseProbeCertificates(stdout: String): List<Pkcs11DiagnosticsReport.RawNoLoginCert> =
	parseProbeNoLoginCerts(stdout).map { parsed ->
		Pkcs11DiagnosticsReport.RawNoLoginCert(
			subjectDN = parsed.certificate.subjectX500Principal.name,
			issuerDN = parsed.certificate.issuerX500Principal.name,
			serialNumber = parsed.certificate.serialNumber.toString(),
			ckaId = parsed.ckaIdHex,
			label = parsed.label,
			slotId = parsed.slotId,
		)
	}
