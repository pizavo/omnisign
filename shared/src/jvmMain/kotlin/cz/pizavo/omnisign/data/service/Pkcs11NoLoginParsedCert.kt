package cz.pizavo.omnisign.data.service

import java.security.cert.X509Certificate

/**
 * A `CERT` line from a `--certs` [Pkcs11ProbeWorker] run, parsed in the parent process
 * (where an X.509 decode failure is harmless) into a usable certificate plus its PKCS#11
 * object metadata.
 *
 * Single source of truth shared by the diagnostics report ([parseProbeCertificates]) and
 * the no-login discovery path ([DssTokenService.listCertificatesNoLogin]).
 *
 * @property certificate The decoded X.509 certificate (`CKA_VALUE`).
 * @property ckaIdHex Lower-case hex of the object's `CKA_ID` (empty when absent).
 * @property label The object's `CKA_LABEL` (decoded UTF-8; empty when absent).
 * @property slotId Slot the certificate was found in.
 */
internal data class Pkcs11NoLoginParsedCert(
	val certificate: X509Certificate,
	val ckaIdHex: String,
	val label: String,
	val slotId: Long,
)
