package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * Type of trust service represented by a directly trusted certificate.
 */
@Serializable
enum class TrustedCertificateType {
	/**
	 * The certificate is trusted for any purpose (both CA and TSA).
	 * Use this for root certificates that can certify both CA and TSA sub-certificates,
	 * or when the specific role is not known or not relevant.
	 */
	ANY,
	
	/**
	 * Certificate Authority — the certificate is trusted as a CA root or intermediate.
	 */
	CA,
	
	/**
	 * Time Stamping Authority — the certificate is trusted as a TSA signer.
	 */
	TSA
}

/**
 * A directly trusted certificate stored inline in the application configuration.
 *
 * Unlike [CustomTrustedListConfig], which references an external ETSI TS 119612 XML
 * document, this stores the certificate's raw DER bytes (Base64-encoded) directly
 * inside the config. This makes the entry self-contained and immune to the original
 * certificate file being moved or deleted.
 *
 * At registration time the certificate file is read, parsed, and its DER encoding
 * and subject DN are extracted. The original file is no longer needed afterward.
 */
@Serializable
data class TrustedCertificateConfig(
	/**
	 * Human-readable label used to reference this certificate in CLI commands and profiles.
	 */
	val name: String,
	
	/**
	 * Type of trust service this certificate represents.
	 */
	val type: TrustedCertificateType,
	
	/**
	 * Base64-encoded DER representation of the X.509 certificate.
	 */
	val certificateBase64: String,
	
	/**
	 * Subject distinguished name extracted from the certificate at registration time.
	 * Stored for display purposes, so listing trusted certificates does not require
	 * decoding the Base64 blob.
	 */
	val subjectDN: String,
)

