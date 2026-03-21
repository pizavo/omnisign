package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.repository.DssServiceFactory.Companion.TL_CACHE_EXPIRATION_MS
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
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import eu.europa.esig.dss.tsl.job.TLValidationJob
import eu.europa.esig.dss.tsl.source.LOTLSource
import eu.europa.esig.dss.tsl.source.TLSource
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
 * [TLValidationJob], and [PdfBoxNativeObjectFactory] so that the signing,
 * timestamping, archiving, and validation repositories all use identical wiring
 * without duplicating code.
 *
 * Managed by Koin so that [credentialStore] is injected once and reused by all
 * callers of [buildTspSource].
 */
class DssServiceFactory(
	private val credentialStore: CredentialStore
) {
	@Volatile
	private var cachedTlSource: TrustedListsCertificateSource? = null
	
	@Volatile
	private var cachedTlWarnings: List<String> = emptyList()
	
	@Volatile
	private var tlCacheTimestamp: Long = 0L
	
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
	 * Build a [CommonCertificateVerifier] optimized for **signing and archiving**.
	 *
	 * Uses [CommonCertificateVerifier.setCheckRevocationForUntrustedChains] so that DSS
	 * fetches and embeds CRL/OCSP revocation data even without loading the full EU LOTL.
	 * This avoids the expensive TL download/parse that validation requires, keeping the
	 * signing operation fast (seconds instead of minutes).
	 *
	 * @param alertFactory Optional factory for the [StatusAlert] wired to all five verifier
	 *   alert properties.  Pass a [CollectingStatusAlert] to capture warnings
	 *   programmatically; defaults to [LogOnStatusAlert] at WARN level.
	 */
	fun buildSigningCertificateVerifier(
		config: ResolvedConfig?,
		alertFactory: () -> StatusAlert = { LogOnStatusAlert(Level.WARN) },
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
		
		val alert = alertFactory()
		return cv.apply {
			aiaSource = DefaultAIASource(dataLoader)
			ocspSource = OnlineOCSPSource().apply { setDataLoader(dataLoader) }
			crlSource = OnlineCRLSource().apply { setDataLoader(dataLoader) }
			isCheckRevocationForUntrustedChains = true
			alertOnMissingRevocationData = alert
			alertOnUncoveredPOE = alert
			alertOnInvalidTimestamp = alert
			alertOnNoRevocationAfterBestSignatureTime = alert
			alertOnRevokedCertificate = alert
		}
	}
	
	/**
	 * Build a [CommonCertificateVerifier] optimized for **validation**.
	 *
	 * Loads EU LOTL and custom trusted-list sources so DSS can assess eIDAS qualification
	 * and build a full trust chain.  The parsed [TrustedListsCertificateSource] is cached
	 * in memory for [TL_CACHE_EXPIRATION_MS] to avoid re-downloading and reparsing on
	 * every validation call.
	 *
	 * @param alertFactory Optional factory for the [StatusAlert] wired to all five verifier
	 *   alert properties.  Pass a [CollectingStatusAlert] to capture warnings
	 *   programmatically; defaults to [LogOnStatusAlert] at WARN level.
	 * @return A [CertificateVerifierResult] containing the verifier and any TL loading warnings.
	 */
	fun buildValidationCertificateVerifier(
		config: ResolvedConfig?,
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
		
		val alert = alertFactory()
		cv.apply {
			aiaSource = DefaultAIASource(dataLoader)
			ocspSource = OnlineOCSPSource().apply { setDataLoader(dataLoader) }
			crlSource = OnlineCRLSource().apply { setDataLoader(dataLoader) }
			alertOnMissingRevocationData = alert
			alertOnUncoveredPOE = alert
			alertOnInvalidTimestamp = alert
			alertOnNoRevocationAfterBestSignatureTime = alert
			alertOnRevokedCertificate = alert
		}
		
		val tlWarnings = wireTrustedSources(cv, config, dataLoader)
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
	
	/**
	 * Load EU LOTL and/or custom trusted lists into [cv] when [config] enables them.
	 *
	 * The parsed [TrustedListsCertificateSource] is cached in memory so that repeated calls
	 * within [TL_CACHE_EXPIRATION_MS] reuse the same instance. A [TLValidationJob] with a
	 * persistent file-backed cache handles the network layer so that TL XML files are only
	 * re-downloaded when the on-disk copy has expired.
	 *
	 * @return User-readable warnings for every TL that could not be refreshed, or an empty
	 *   list when no TL loading is required or all TLs loaded successfully.
	 */
	private fun wireTrustedSources(
		cv: CommonCertificateVerifier,
		config: ResolvedConfig,
		dataLoader: CommonsDataLoader
	): List<String> {
		if (!config.validation.useEuLotl && config.validation.customTrustedLists.isEmpty()) {
			return emptyList()
		}
		
		val now = System.currentTimeMillis()
		val cached = cachedTlSource
		if (cached != null && (now - tlCacheTimestamp) < TL_CACHE_EXPIRATION_MS) {
			cv.setTrustedCertSources(cached)
			return cachedTlWarnings
		}
		
		val tlCertSource = TrustedListsCertificateSource()
		val job = buildTLValidationJob(config, tlCertSource, dataLoader)
		job.onlineRefresh()
		val warnings = collectTlWarnings(job.summary)
		
		cachedTlSource = tlCertSource
		cachedTlWarnings = warnings
		tlCacheTimestamp = now
		
		cv.setTrustedCertSources(tlCertSource)
		return warnings
	}
	
	/**
	 * Build and return a [TLValidationJob] wired with EU LOTL and/or custom TL sources.
	 *
	 * Uses a persistent, platform-appropriate cache directory, so the LOTL and member-state
	 * trusted lists are only re-downloaded when the cached copy is older than
	 * [TL_CACHE_EXPIRATION_MS]. An offline loader backed by the same directory ensures
	 * that already-cached responses are served without any network access on subsequent
	 * calls within the expiration window.
	 */
	private fun buildTLValidationJob(
		config: ResolvedConfig,
		tlCertSource: TrustedListsCertificateSource,
		dataLoader: CommonsDataLoader
	): TLValidationJob {
		val cacheDir = tlCacheDir().also { it.mkdirs() }
		
		val offlineLoader = FileCacheDataLoader().apply {
			setCacheExpirationTime(CACHE_NEVER_EXPIRE)
			setFileCacheDirectory(cacheDir)
		}
		
		val onlineLoader = FileCacheDataLoader().apply {
			setCacheExpirationTime(TL_CACHE_EXPIRATION_MS)
			setDataLoader(dataLoader)
			setFileCacheDirectory(cacheDir)
		}
		
		val job = TLValidationJob().apply {
			setTrustedListCertificateSource(tlCertSource)
			setOfflineDataLoader(offlineLoader)
			setOnlineDataLoader(onlineLoader)
		}
		
		if (config.validation.useEuLotl) {
			val lotlSource = LOTLSource().apply {
				url = EU_LOTL_URL
				certificateSource = buildOjCertificateSource()
				isPivotSupport = true
			}
			job.setListOfTrustedListSources(lotlSource)
		}
		
		if (config.validation.customTrustedLists.isNotEmpty()) {
			val tlSources = config.validation.customTrustedLists.map { tl ->
				TLSource().apply {
					url = tl.source
					tl.signingCertPath?.let { certPath ->
						certificateSource = buildCertSourceFromFile(certPath)
					}
				}
			}.toTypedArray()
			job.setTrustedListSources(*tlSources)
		}
		
		return job
	}
	
	companion object {
		/**
		 * Load the Official Journal (OJ) keystore bundled as a classpath resource and wrap it
		 * in a [CommonTrustedCertificateSource] so DSS can verify EU LOTL pivot signatures.
		 *
		 * The keystore is the pre-configured one from the dss-demonstrations repository and
		 * contains the EC's LOTL signing certificates published in the Official Journal.
		 */
		private fun buildOjCertificateSource(): CommonTrustedCertificateSource {
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
		private fun buildCertSourceFromFile(certPath: String): CommonTrustedCertificateSource {
			val x509 = File(certPath).inputStream().use { stream ->
				java.security.cert.CertificateFactory.getInstance("X.509")
					.generateCertificate(stream) as java.security.cert.X509Certificate
			}
			val token = eu.europa.esig.dss.model.x509.CertificateToken(x509)
			return CommonTrustedCertificateSource().also { it.addCertificate(token) }
		}
		
		/**
		 * Inspect a post-refresh [TLValidationJobSummary] and return user-readable warning strings
		 * for every member-state trusted list that could not be downloaded or parsed.
		 *
		 * Only download failures are reported; partial parse failures (e.g., old certificate
		 * entries inside an otherwise intact TL) are treated as non-actionable noise and
		 * omitted intentionally.
		 */
		private fun collectTlWarnings(summary: TLValidationJobSummary): List<String> {
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
		
		const val EU_LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml"
		const val OJ_KEYSTORE_RESOURCE = "/lotl-keystore.p12"
		const val OJ_KEYSTORE_TYPE = "PKCS12"
		const val OJ_KEYSTORE_PASSWORD = "dss-password"
		
		/** 24 hours — how long a cached TL response is considered fresh before re-downloading. */
		const val TL_CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L
		
		/** Sentinel for [FileCacheDataLoader.setCacheExpirationTime]: never re-download. */
		const val CACHE_NEVER_EXPIRE = -1L
	}
}
