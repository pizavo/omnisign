package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import eu.europa.esig.dss.alert.LogOnStatusAlert
import eu.europa.esig.dss.pdf.PdfMemoryUsageSetting
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.HostConnection
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader
import eu.europa.esig.dss.service.http.commons.UserCredentials
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource
import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import org.slf4j.event.Level

/**
 * Shared factory for DSS infrastructure objects used across PAdES repositories.
 *
 * Centralizes construction of [OnlineTSPSource], [CommonCertificateVerifier], and
 * [PdfBoxNativeObjectFactory] so that the signing, timestamping, and archiving
 * repositories all use identical wiring without duplicating code.
 */
internal object DssServiceFactory {
	/**
	 * Build an [OnlineTSPSource] for [tsConfig], resolving the HTTP Basic password from
	 * [credentialStore] when a credential key is configured on the server.
	 */
	fun buildTspSource(tsConfig: TimestampServerConfig, credentialStore: CredentialStore): OnlineTSPSource {
		val password = tsConfig.runtimePassword
			?: tsConfig.credentialKey?.let { key ->
				credentialStore.getPassword(TSA_CREDENTIAL_SERVICE, key)
			}

		val dataLoader = TimestampDataLoader().apply {
			timeoutConnection = tsConfig.timeout
			timeoutSocket = tsConfig.timeout
			if (tsConfig.username != null && password != null) {
				val uri = java.net.URI.create(tsConfig.url)
				val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT
				addAuthentication(
					HostConnection(uri.host, port),
					UserCredentials(tsConfig.username, password.toCharArray())
				)
			}
		}

		return OnlineTSPSource(tsConfig.url).apply { setDataLoader(dataLoader) }
	}


	/**
	 * Build a [CommonCertificateVerifier] from an optional [ResolvedConfig].
	 *
	 * When [config] is null or revocation checking is disabled, returns a lenient offline
	 * verifier with all alerts suppressed. Otherwise, returns a fully wired online verifier.
	 * The [onDataLoaderCreated] callback receives the [CommonsDataLoader] that was created,
	 * allowing callers to reuse the same connection pool (e.g., for wiring a TLValidationJob).
	 */
	fun buildCertificateVerifier(
		config: ResolvedConfig?,
		onDataLoaderCreated: ((CommonsDataLoader) -> Unit)? = null
	): CommonCertificateVerifier {
		val cv = CommonCertificateVerifier()

		if (config == null || !config.validation.checkRevocation) {
			return cv.apply {
				alertOnMissingRevocationData = null
				alertOnUncoveredPOE = null
				alertOnInvalidTimestamp = null
				alertOnNoRevocationAfterBestSignatureTime = null
				alertOnRevokedCertificate = null
			}
		}

		val timeout = minOf(config.ocsp.timeout, config.crl.timeout)
		val dataLoader = CommonsDataLoader().apply {
			timeoutConnection = timeout
			timeoutSocket = timeout
		}
		onDataLoaderCreated?.invoke(dataLoader)

		return cv.apply {
			aiaSource = DefaultAIASource(dataLoader)
			ocspSource = OnlineOCSPSource().apply { setDataLoader(dataLoader) }
			crlSource = OnlineCRLSource().apply { setDataLoader(dataLoader) }
			alertOnMissingRevocationData = LogOnStatusAlert(Level.WARN)
			alertOnUncoveredPOE = LogOnStatusAlert(Level.WARN)
			alertOnInvalidTimestamp = LogOnStatusAlert(Level.WARN)
			alertOnNoRevocationAfterBestSignatureTime = LogOnStatusAlert(Level.WARN)
			alertOnRevokedCertificate = LogOnStatusAlert(Level.WARN)
		}
	}

	/**
	 * Build a lenient offline [CommonCertificateVerifier] with a configurable [timeout] for
	 * the underlying HTTP data loaders, without requiring a full [ResolvedConfig].
	 */
	fun buildCertificateVerifier(timeout: Int = DEFAULT_TIMEOUT): CommonCertificateVerifier {
		val dataLoader = CommonsDataLoader().apply {
			timeoutConnection = timeout
			timeoutSocket = timeout
		}
		return CommonCertificateVerifier().apply {
			aiaSource = DefaultAIASource(dataLoader)
			ocspSource = OnlineOCSPSource().apply { setDataLoader(dataLoader) }
			crlSource = OnlineCRLSource().apply { setDataLoader(dataLoader) }
		}
	}

	/**
	 * Build a memory-efficient [PdfBoxNativeObjectFactory] that spills large documents to a
	 * temporary file once the in-heap limit is exceeded.
	 */
	fun buildPdfObjectFactory(): PdfBoxNativeObjectFactory =
		PdfBoxNativeObjectFactory().apply {
			setPdfMemoryUsageSetting(
				PdfMemoryUsageSetting.mixed(MEMORY_LIMIT_BYTES, TEMP_FILE_LIMIT_BYTES)
			)
		}

	private const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
	private const val HTTPS_PORT = 443
	private const val HTTP_PORT = 80
	private const val MEMORY_LIMIT_BYTES = 32L * 1024 * 1024
	private const val TEMP_FILE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024
	private const val DEFAULT_TIMEOUT = 30_000
}
