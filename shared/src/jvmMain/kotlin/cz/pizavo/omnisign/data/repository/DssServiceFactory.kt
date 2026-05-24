package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import eu.europa.esig.dss.alert.LogOnStatusAlert
import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.model.tsl.TLValidationJobSummary
import eu.europa.esig.dss.pdf.PdfMemoryUsageSetting
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.service.crl.OnlineCRLSource
import eu.europa.esig.dss.service.http.commons.*
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource

import eu.europa.esig.dss.service.tsp.OnlineTSPSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import org.slf4j.event.Level
import java.io.File

/**
 * Result of building a [CommonCertificateVerifier] with optional trusted-list wiring.
 *
 * @property verifier The fully configured certificate verifier.
 * @property tlWarnings Non-fatal warnings from trusted-list loading (e.g., unreachable TL hosts).
 */
data class CertificateVerifierResult(
	val verifier: CommonCertificateVerifier,
	val tlWarnings: List<String> = emptyList()
)

/**
 * Shared factory for DSS infrastructure objects used across PAdES repositories.
 *
 * Centralizes construction of [OnlineTSPSource], [CommonCertificateVerifier],
 * and [PdfBoxNativeObjectFactory] so that the signing, timestamping, archiving,
 * and validation repositories all use identical wiring without duplicating code.
 *
 * Trusted-list wiring is delegated to a shared [TrustedSourceRegistry] that
 * retains one [eu.europa.esig.dss.tsl.job.TLValidationJob] per distinct
 * trust-source identity (one shared EU LOTL, one per custom list) so the
 * expensive parse + signature-verification of a trusted list is paid once and
 * skipped on subsequent unchanged refreshes.
 *
 * Managed by Koin so that [credentialStore] is injected once and the registry is
 * shared across all callers.
 */
class DssServiceFactory(
	private val credentialStore: CredentialStore,
	private val trustedSources: TrustedSourceRegistry = TrustedSourceRegistry(),
) {

	/**
	 * Build an [OnlineTSPSource] for [tsConfig], resolving the HTTP Basic password from
	 * the injected [CredentialStore] when a credential key is configured on the server.
	 */
	fun buildTspSource(tsConfig: TimestampServerConfig): OnlineTSPSource {
		val password = tsConfig.runtimePassword?.value
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
	 * Build a [CommonCertificateVerifier] for **signing and archiving** with full trusted-list
	 * support.
	 *
	 * Loads EU LOTL and custom trusted-list sources so that DSS trusts TSA certificate chains
	 * and can fetch and verify CRL/OCSP revocation data during the signing operation.
	 * [CommonCertificateVerifier.setCheckRevocationForUntrustedChains] is intentionally
	 * **disabled** — with trusted lists loaded, revocation data for all relevant chains is
	 * already fetched. Enabling it would cause spurious warnings for auxiliary certificates
	 * (e.g. OCSP responder certs) that legitimately have no CRL/OCSP endpoint.
	 *
	 * [CommonCertificateVerifier.setAlertOnMissingRevocationData] and
	 * [CommonCertificateVerifier.setAlertOnNoRevocationAfterBestSignatureTime] are
	 * intentionally **suppressed** for signing. These alerts fire during the verifier's
	 * internal pre-extension check, before the PAdES extension process downloads and embeds
	 * CRL/OCSP data. If the extension fails, DSS throws an exception; if it succeeds, the
	 * revocation data IS embedded and the alerts are false positives. Keeping them active
	 * would produce misleading "revocation data unavailable" warnings on every B-LT/B-LTA
	 * signing operation even though the output document contains valid revocation data.
	 *
	 * The three alerts that remain active detect genuinely actionable conditions:
	 * - [CommonCertificateVerifier.setAlertOnUncoveredPOE] — proof-of-existence gaps.
	 * - [CommonCertificateVerifier.setAlertOnInvalidTimestamp] — timestamp validation failures.
	 * - [CommonCertificateVerifier.setAlertOnRevokedCertificate] — revoked signing certificate.
	 *
	 * Trusted-list sources are supplied by the shared [TrustedSourceRegistry], which
	 * retains the parsed EU LOTL and custom-list sources across calls so the LOTL
	 * parse + signature-verification overhead is incurred at most once and refreshed
	 * on the background cycle rather than on this call. [directAnchors] (the app-managed
	 * trust store's certificates for the active scope) are aggregated alongside them.
	 *
	 * @param config Resolved configuration driving revocation and trusted-list selection; a `null`
	 *   config or one with revocation disabled yields a lenient verifier with alerts suppressed.
	 * @param directAnchors Directly-trusted certificates (from the trust store) aggregated with the
	 *   trusted-list sources.
	 * @param alertFactory Optional factory for the [StatusAlert] wired to the verifier
	 *   alert properties that remain active during signing.  Pass a [CollectingStatusAlert]
	 *   to capture warnings programmatically; defaults to [LogOnStatusAlert] at WARN level.
	 * @return A [CertificateVerifierResult] containing the verifier and any TL loading warnings.
	 */
	fun buildSigningCertificateVerifier(
		config: ResolvedConfig?,
		directAnchors: List<cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor> = emptyList(),
		alertFactory: () -> StatusAlert = { LogOnStatusAlert(Level.WARN) },
	): CertificateVerifierResult {
		val cv = CommonCertificateVerifier()
		
		if (config == null || !config.validation.checkRevocation) {
			return CertificateVerifierResult(
				verifier = cv.apply {
					alertOnMissingRevocationData = null
					alertOnUncoveredPOE = null
					alertOnInvalidTimestamp = null
					alertOnNoRevocationAfterBestSignatureTime = null
					alertOnRevokedCertificate = null
				}
			)
		}
		
		val timeout = minOf(config.ocsp.timeout, config.crl.timeout)
		val dataLoader = CommonsDataLoader().apply {
			timeoutConnection = timeout
			timeoutSocket = timeout
		}
		val ocspLoader = OCSPDataLoader().apply {
			timeoutConnection = timeout
			timeoutSocket = timeout
		}
		
		val alert = alertFactory()
		cv.apply {
			aiaSource = DefaultAIASource(dataLoader)
			ocspSource = OnlineOCSPSource().apply { setDataLoader(ocspLoader) }
			crlSource = OnlineCRLSource().apply { setDataLoader(dataLoader) }
			isCheckRevocationForUntrustedChains = false
			alertOnMissingRevocationData = null
			alertOnUncoveredPOE = alert
			alertOnInvalidTimestamp = alert
			alertOnNoRevocationAfterBestSignatureTime = null
			alertOnRevokedCertificate = alert
		}
		
		val tlWarnings = trustedSources.composeInto(cv, config, directAnchors)
		return CertificateVerifierResult(cv, tlWarnings)
	}

	/**
	 * Build a [CommonCertificateVerifier] optimized for **validation**.
	 *
	 * Loads EU LOTL and custom trusted-list sources so DSS can assess eIDAS qualification
	 * and build a full trust chain.  Sources come from the shared [TrustedSourceRegistry],
	 * which retains them across calls so neither the LOTL download nor its parse and
	 * signature-verification are repeated on every validation. [directAnchors] (the app-managed
	 * trust store's certificates for the active scope) are aggregated alongside them.
	 *
	 * @param config Resolved configuration driving revocation and trusted-list selection; a `null`
	 *   config or one with revocation disabled yields a lenient verifier with alerts suppressed.
	 * @param directAnchors Directly-trusted certificates (from the trust store) aggregated with the
	 *   trusted-list sources.
	 * @param alertFactory Optional factory for the [StatusAlert] wired to all five verifier
	 *   alert properties.  Pass a [CollectingStatusAlert] to capture warnings
	 *   programmatically; defaults to [LogOnStatusAlert] at WARN level.
	 * @return A [CertificateVerifierResult] containing the verifier and any TL loading warnings.
	 */
	fun buildValidationCertificateVerifier(
		config: ResolvedConfig?,
		directAnchors: List<cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor> = emptyList(),
		alertFactory: () -> StatusAlert = { LogOnStatusAlert(Level.WARN) },
	): CertificateVerifierResult {
		val cv = CommonCertificateVerifier()
		
		if (config == null || !config.validation.checkRevocation) {
			return CertificateVerifierResult(
				verifier = cv.apply {
					alertOnMissingRevocationData = null
					alertOnUncoveredPOE = null
					alertOnInvalidTimestamp = null
					alertOnNoRevocationAfterBestSignatureTime = null
					alertOnRevokedCertificate = null
				}
			)
		}
		
		val timeout = minOf(config.ocsp.timeout, config.crl.timeout)
		val dataLoader = CommonsDataLoader().apply {
			timeoutConnection = timeout
			timeoutSocket = timeout
		}
		val ocspLoader = OCSPDataLoader().apply {
			timeoutConnection = timeout
			timeoutSocket = timeout
		}
		
		val alert = alertFactory()
		cv.apply {
			aiaSource = DefaultAIASource(dataLoader)
			ocspSource = OnlineOCSPSource().apply { setDataLoader(ocspLoader) }
			crlSource = OnlineCRLSource().apply { setDataLoader(dataLoader) }
			alertOnMissingRevocationData = alert
			alertOnUncoveredPOE = alert
			alertOnInvalidTimestamp = alert
			alertOnNoRevocationAfterBestSignatureTime = alert
			alertOnRevokedCertificate = alert
		}
		
		val tlWarnings = trustedSources.composeInto(cv, config, directAnchors)
		return CertificateVerifierResult(cv, tlWarnings)
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
		val ocspLoader = OCSPDataLoader().apply {
			timeoutConnection = timeout
			timeoutSocket = timeout
		}
		return CommonCertificateVerifier().apply {
			aiaSource = DefaultAIASource(dataLoader)
			ocspSource = OnlineOCSPSource().apply { setDataLoader(ocspLoader) }
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
	
	/**
	 * Eagerly build and warm the trusted sources for [useEuLotl] and [customTls] so
	 * the first validation/signing operation hits already-parsed sources instead of
	 * paying the LOTL parse on its critical path. Intended for the startup warmup of
	 * long-running hosts (desktop, server); the one-shot CLI does not call this.
	 */
	fun warmUpTrustedSources(useEuLotl: Boolean, customTls: List<CustomTrustedListConfig>) {
		trustedSources.warmUp(useEuLotl, customTls)
	}

	/**
	 * Online-refresh every retained trusted source as one coherent set. Invoked by
	 * the global refresh cycle; cheap when upstream content is unchanged.
	 */
	fun refreshTrustedSources() {
		trustedSources.refreshAll()
	}

	/**
	 * Hard-refresh every retained trusted source, forcing a real network
	 * re-download regardless of cache freshness. Backs the user-initiated
	 * "Refresh now" (desktop) and `config tl refresh` (CLI); the scheduled cycle
	 * uses the cache-gated [refreshTrustedSources] instead.
	 */
	fun forceRefreshTrustedSources() {
		trustedSources.forceRefreshAll()
	}

	/**
	 * Set the process-global trusted-list re-download interval, in milliseconds,
	 * derived from `GlobalConfig.trustedListRefreshIntervalHours`. Must be applied
	 * before the first verifier is built so the persistent online loader picks it up.
	 */
	fun configureTrustedListRefreshInterval(intervalMillis: Long) {
		trustedSources.cacheExpirationMillis = intervalMillis
	}

	/**
	 * Release the registry's shared refresh executor. Called when a long-running
	 * host shuts down; the CLI relies on daemon threads and need not call this.
	 */
	fun shutdownTrustedSources() {
		trustedSources.shutdown()
	}

	companion object {
		/**
		 * Load the Official Journal (OJ) keystore bundled as a classpath resource and wrap it
		 * in a [CommonTrustedCertificateSource] so DSS can verify EU LOTL pivot signatures.
		 *
		 * The keystore is the pre-configured one from the dss-demonstrations repository and
		 * contains the EC's LOTL signing certificates published in the Official Journal.
		 */
		internal fun buildOjCertificateSource(): CommonTrustedCertificateSource {
			val keystoreStream = DssServiceFactory::class.java
				.getResourceAsStream(OJ_KEYSTORE_RESOURCE)
				?: error(
					"OJ keystore not found on classpath: $OJ_KEYSTORE_RESOURCE. " +
							"Run './gradlew :shared:updateLotlKeystore' to download it, then rebuild."
				)
			
			val keystore =
				KeyStoreCertificateSource(keystoreStream, OJ_KEYSTORE_TYPE, OJ_KEYSTORE_PASSWORD.toCharArray())
			return CommonTrustedCertificateSource().also { it.importAsTrusted(keystore) }
		}
		
		/**
		 * Returns the platform-appropriate persistent directory for caching downloaded
		 * trusted lists (EU LOTL and member-state TLs).
		 *
		 * - **Windows**: `%LOCALAPPDATA%\omnisign\tl-cache`
		 * - **macOS**: `~/Library/Caches/omnisign/tl-cache`
		 * - **Linux / other**: `~/.cache/omnisign/tl-cache`
		 */
		internal fun tlCacheDir(): File {
			val os = System.getProperty("os.name", "").lowercase()
			val userHome = System.getProperty("user.home")
			val base = when {
				os.contains("win") ->
					System.getenv("LOCALAPPDATA")?.let { File(it, "omnisign") }
						?: File(userHome, "AppData/Local/omnisign")
				
				os.contains("mac") ->
					File(userHome, "Library/Caches/omnisign")
				
				else ->
					System.getenv("XDG_CACHE_HOME")?.let { File(it, "omnisign") }
						?: File(userHome, ".cache/omnisign")
			}
			return File(base, "tl-cache")
		}
		
		/**
		 * Build a [CommonTrustedCertificateSource] from a PEM or DER certificate file on disk.
		 * Used to supply per-TL signing certificates for custom [TLSource] instances.
		 */
		internal fun buildCertSourceFromFile(certPath: String): CommonTrustedCertificateSource {
			val x509 = File(certPath).inputStream().use { stream ->
				java.security.cert.CertificateFactory.getInstance("X.509")
					.generateCertificate(stream) as java.security.cert.X509Certificate
			}
			val token = eu.europa.esig.dss.model.x509.CertificateToken(x509)
			return CommonTrustedCertificateSource().also { it.addCertificate(token) }
		}
		
		/**
		 * Build a [CommonTrustedCertificateSource] from resolved trust anchors (canonical DER).
		 *
		 * @return A populated source, or null when [anchors] is empty.
		 */
		internal fun buildTrustedCertSource(
			anchors: List<cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor>
		): CommonTrustedCertificateSource? {
			if (anchors.isEmpty()) return null
			val source = CommonTrustedCertificateSource()
			for (anchor in anchors) {
				val x509 = java.security.cert.CertificateFactory.getInstance("X.509")
					.generateCertificate(anchor.der.inputStream()) as java.security.cert.X509Certificate
				source.addCertificate(eu.europa.esig.dss.model.x509.CertificateToken(x509))
			}
			return source
		}
		
		/**
		 * Inspect a post-refresh [TLValidationJobSummary] and return user-readable warning strings
		 * for every member-state trusted list that could not be downloaded or parsed.
		 *
		 * Only download failures are reported; partial parse failures (e.g., old certificate
		 * entries inside an otherwise intact TL) are treated as non-actionable noise and
		 * omitted intentionally.
		 */
		internal fun collectTlWarnings(summary: TLValidationJobSummary): List<String> {
			val failedHosts = mutableListOf<String>()
			
			for (lotlInfo in summary.lotlInfos) {
				for (tlInfo in lotlInfo.tlInfos) {
					val dl = tlInfo.downloadCacheInfo
					if (dl.isError || !dl.isResultExist) {
						failedHosts += extractTlHost(tlInfo.url)
					}
				}
			}
			
			for (tlInfo in summary.otherTLInfos) {
				val dl = tlInfo.downloadCacheInfo
				if (dl.isError || !dl.isResultExist) {
					failedHosts += extractTlHost(tlInfo.url)
				}
			}
			
			if (failedHosts.isEmpty()) return emptyList()
			
			val plural = if (failedHosts.size == 1) "list" else "lists"
			return listOf(
				"${failedHosts.size} trusted $plural could not be refreshed " +
						"(${failedHosts.joinToString(", ")}). " +
						"Qualification assessment for certificates from these sources may be incomplete."
			)
		}
		
		/**
		 * Extract a short, human-readable host label from a trusted list [url].
		 * Falls back to the raw URL if parsing fails.
		 */
		private fun extractTlHost(url: String): String =
			runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
		
		private const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
		private const val HTTPS_PORT = 443
		private const val HTTP_PORT = 80
		private const val MEMORY_LIMIT_BYTES = 32L * 1024 * 1024
		private const val TEMP_FILE_LIMIT_BYTES = 2L * 1024 * 1024 * 1024
		private const val DEFAULT_TIMEOUT = 30_000

		/**
		 * Process-global connect/read timeout (ms) for fetching trusted-list XML.
		 *
		 * Decoupled from the per-config OCSP/CRL revocation timeout because the EU
		 * LOTL source is shared across every profile and cannot carry a per-profile
		 * timeout — a single shared value is the only coherent choice.
		 */
		internal const val TL_FETCH_TIMEOUT_MS = 30_000
		
		/** URL of the EU List of Trusted Lists (LOTL) XML document. */
		const val EU_LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml"

		/** Classpath location of the pre-built Official Journal (OJ) keystore. */
		const val OJ_KEYSTORE_RESOURCE = "/lotl-keystore.p12"

		/** Keystore type for the OJ keystore. */
		const val OJ_KEYSTORE_TYPE = "PKCS12"

		/**
		 * Well-known password for the pre-built OJ keystore from the EU DSS demonstrations
		 * repository. This is **not a secret** — the keystore contains only public LOTL
		 * signing certificates published in the Official Journal of the EU and is distributed
		 * openly by the European Commission.
		 */
		const val OJ_KEYSTORE_PASSWORD = "dss-password"
		
		/** 24 hours — how long a cached TL response is considered fresh before re-downloading. */
		const val TL_CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L
		
		/** Sentinel for [FileCacheDataLoader.setCacheExpirationTime]: never re-download. */
		const val CACHE_NEVER_EXPIRE = -1L
	}
}
