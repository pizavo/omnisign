package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import eu.europa.esig.dss.alert.LogOnStatusAlert
import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.model.DSSDocument
import eu.europa.esig.dss.model.InMemoryDocument
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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
	 * The expired-signing-certificate alert keeps its default (which aborts signing) unless
	 * [cz.pizavo.omnisign.domain.model.config.ValidationConfig.allowExpiredCertificate] is set, in
	 * which case it is disabled so a signature can be produced with an expired certificate.
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
		if (config?.validation?.allowExpiredCertificate == true) {
			cv.alertOnExpiredCertificate = null
		}

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
	 * Verifier alerts only fire at all because [DssValidationRepository] installs DSS's
	 * [eu.europa.esig.dss.spi.validation.executor.CompleteValidationContextExecutor]; the two
	 * that are left active report the one condition the validation report itself stays silent
	 * about — revocation data that predates the time it has to cover:
	 * - [CommonCertificateVerifier.setAlertOnUncoveredPOE] — for the timestamps' chains, revocation
	 *   data older than the timestamp whose proof-of-existence it has to cover.
	 * - [CommonCertificateVerifier.setAlertOnNoRevocationAfterBestSignatureTime] — the same for the
	 *   signing chain, against the best signature time.
	 *
	 * Both are self-limiting: DSS fetches newer revocation data before the alerts are evaluated, so
	 * they fire only when nothing that covers the signature could be obtained — validating offline,
	 * or against an issuer that no longer publishes, or one still serving the same cached response.
	 *
	 * Every other alert is [silenceAlertsCoveredByTheReport]; see there for why leaving them in
	 * place would be actively harmful.
	 *
	 * @param config Resolved configuration driving revocation and trusted-list selection; a `null`
	 *   config or one with revocation disabled yields a lenient verifier with alerts suppressed.
	 * @param directAnchors Directly-trusted certificates (from the trust store) aggregated with the
	 *   trusted-list sources.
	 * @param alertFactory Optional factory for the [StatusAlert] wired to the two verifier alert
	 *   properties that remain active.  Pass a [CollectingStatusAlert] to capture warnings
	 *   programmatically; defaults to [LogOnStatusAlert] at WARN level.
	 * @return A [CertificateVerifierResult] containing the verifier and any TL loading warnings.
	 */
	fun buildValidationCertificateVerifier(
		config: ResolvedConfig?,
		directAnchors: List<cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor> = emptyList(),
		alertFactory: () -> StatusAlert = { LogOnStatusAlert(Level.WARN) },
	): CertificateVerifierResult {
		val cv = CommonCertificateVerifier()
		cv.silenceAlertsCoveredByTheReport()

		if (config == null || !config.validation.checkRevocation) {
			return CertificateVerifierResult(
				verifier = cv.apply {
					alertOnUncoveredPOE = null
					alertOnNoRevocationAfterBestSignatureTime = null
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
			alertOnUncoveredPOE = alert
			alertOnNoRevocationAfterBestSignatureTime = alert
		}

		val tlWarnings = trustedSources.composeInto(cv, config, directAnchors)
		return CertificateVerifierResult(cv, tlWarnings)
	}

	/**
	 * Disable the verifier alerts whose conditions the validation report already states as ETSI
	 * indications, so that enabling the alerter for validation adds no duplicate warnings.
	 *
	 * A revoked certificate, an invalid timestamp and missing revocation data each drive the
	 * report's own indication and sub-indication, which say more than an alert could.
	 *
	 * [CommonCertificateVerifier.setAlertOnExpiredCertificate] and
	 * [CommonCertificateVerifier.setAlertOnNotYetValidCertificate] must be silenced for a second,
	 * harder reason: DSS defaults both to [eu.europa.esig.dss.alert.ExceptionOnStatusAlert], which
	 * throws rather than reports. With the alerter running, a document they fire on would come back
	 * as a failed *operation* — no report, no indication, nothing for the user to read — because the
	 * throw escapes before any report is built.
	 *
	 * They fire on a signature whose certificate has expired *and* that has no proof-of-existence
	 * within the certificate's validity, so a timestamped signature is unaffected: its timestamp is
	 * that proof. The document they would abort on is the un-timestamped one whose certificate has
	 * since expired — exactly the case the report handles well on its own, returning INDETERMINATE
	 * with `OUT_OF_BOUNDS_NO_POE` and saying why.
	 */
	private fun CommonCertificateVerifier.silenceAlertsCoveredByTheReport() {
		alertOnMissingRevocationData = null
		alertOnInvalidTimestamp = null
		alertOnRevokedCertificate = null
		alertOnExpiredCertificate = null
		alertOnNotYetValidCertificate = null
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
		 * Classpath locations (DER) of the national eIDAS roots that augment the
		 * platform trust for trusted-list **transport**. Each is the exact CA anchor a
		 * member-state TL endpoint chains to over TLS but JetBrains Runtime's trimmed
		 * `cacerts` omits: `AC RAIZ FNMT-RCM` (ES, tsl.digital.gob.es),
		 * `Certum Trusted Root CA` (PL, www.nccert.pl), and
		 * `Microsec e-Szigno Root CA 2009` (HU, www.nmhh.hu).
		 *
		 * Exported from a full Mozilla-tracking `cacerts` (Temurin). Extend this list
		 * only when another member-state endpoint is observed failing for a missing
		 * root — not pre-emptively, to keep the bundled set minimal.
		 */
		private val TL_TRANSPORT_ROOT_RESOURCES = listOf(
			"/tl-transport-roots/ac-raiz-fnmt-rcm.der",
			"/tl-transport-roots/certum-trusted-root-ca.der",
			"/tl-transport-roots/microsec-e-szigno-root-ca-2009.der",
		)

		/** Keystore type of the in-memory [configureTlTransportTrust] truststore. */
		private const val TL_TRUSTSTORE_TYPE = "PKCS12"

		/**
		 * Throwaway integrity password for the in-memory TL-transport truststore.
		 * **Not a secret**: the store is never persisted and holds only public CA
		 * certificates; PKCS#12 simply requires a password to seal the document.
		 */
		private const val TL_TRUSTSTORE_PASSWORD = "omnisign-tl-transport"

		/**
		 * In-memory PKCS#12 truststore for trusted-list **transport**: the running
		 * JVM's default trust anchors augmented with the bundled national eIDAS roots
		 * in [TL_TRANSPORT_ROOT_RESOURCES].
		 *
		 * Built once per process — the trust material is constant and shared by every
		 * online loader, so re-enumerating the platform anchors and re-serializing the
		 * keystore on each loader would be wasted work.
		 *
		 * On a JVM whose `cacerts` already carries these roots (Temurin on the
		 * server/CLI) the augmentation is a harmless superset; on JetBrains Runtime
		 * (desktop), whose trimmed `cacerts` omits them, it supplies the otherwise
		 * missing anchors so the ES/PL/HU member-state lists can be fetched over TLS.
		 * Full certification-path validation is preserved — no trust-all strategy is
		 * used, so transport authenticity is not weakened.
		 */
		private val tlTransportTrustStore: DSSDocument by lazy { buildTlTransportTrustStore() }

		/**
		 * Apply the augmented [tlTransportTrustStore] to [loader] so trusted-list
		 * downloads succeed on JVMs whose default `cacerts` lacks the bundled national
		 * roots. The supplied truststore **replaces** the loader's default SSL trust
		 * (Apache's builder does not union a configured store with `cacerts`), which is
		 * why [tlTransportTrustStore] is itself a superset of the platform anchors.
		 */
		internal fun configureTlTransportTrust(loader: CommonsDataLoader) {
			loader.setSslTruststore(tlTransportTrustStore)
			loader.setSslTruststoreType(TL_TRUSTSTORE_TYPE)
			loader.setSslTruststorePassword(TL_TRUSTSTORE_PASSWORD.toCharArray())
		}

		/**
		 * Build [tlTransportTrustStore]: load every platform trust anchor under a
		 * distinct alias, add each bundled root from [TL_TRANSPORT_ROOT_RESOURCES] the
		 * platform does not already trust (deduplicated by encoded form, so a full
		 * `cacerts` gains no duplicate), and serialize the result to an in-memory
		 * PKCS#12 document.
		 */
		private fun buildTlTransportTrustStore(): DSSDocument {
			val keyStore = KeyStore.getInstance(TL_TRUSTSTORE_TYPE).apply { load(null, null) }
			val certFactory = CertificateFactory.getInstance("X.509")

			val platformAnchors = platformTrustAnchors()
			platformAnchors.forEachIndexed { index, anchor ->
				keyStore.setCertificateEntry("default-$index", anchor)
			}

			val trusted = platformAnchors.toHashSet()
			TL_TRANSPORT_ROOT_RESOURCES.forEachIndexed { index, resource ->
				val root = DssServiceFactory::class.java.getResourceAsStream(resource)?.use { stream ->
					certFactory.generateCertificate(stream) as X509Certificate
				} ?: error("Bundled TL-transport root not found on classpath: $resource")
				if (trusted.add(root)) {
					keyStore.setCertificateEntry("bundled-$index", root)
				}
			}

			val bytes = ByteArrayOutputStream().use { out ->
				keyStore.store(out, TL_TRUSTSTORE_PASSWORD.toCharArray())
				out.toByteArray()
			}
			return InMemoryDocument(bytes)
		}

		/**
		 * The running JVM's default X.509 trust anchors — the issuers a default
		 * [TrustManagerFactory] (initialized from the platform `cacerts`) would accept.
		 * Empty only on a JVM exposing no default [X509TrustManager], which does not
		 * occur in practice.
		 */
		private fun platformTrustAnchors(): List<X509Certificate> {
			val trustManagerFactory =
				TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
			trustManagerFactory.init(null as KeyStore?)
			return trustManagerFactory.trustManagers
				.filterIsInstance<X509TrustManager>()
				.firstOrNull()
				?.acceptedIssuers
				?.toList()
				.orEmpty()
		}

		/** System property toggling JDK AIA *caIssuers* fetching during path building. */
		private const val AIA_CA_ISSUERS_PROPERTY = "com.sun.security.enableAIAcaIssuers"

		/**
		 * System property holding the allowlist of permitted AIA fetch locations
		 * (deny-all by default on recent JDKs).
		 */
		private const val ALLOWED_AIA_LOCATIONS_PROPERTY = "com.sun.security.allowedAIALocations"

		/**
		 * The single AIA location permitted by [enableAiaCaIssuerFetching]: DigiCert's
		 * public certificate repository, source of the one intermediate the known
		 * incomplete-chain endpoint (`eidas.gov.ie`) omits.
		 */
		private const val DIGICERT_AIA_REPOSITORY = "http://cacerts.digicert.com"

		/**
		 * Enable JDK AIA *caIssuers* fetching, narrowly allowlisted to DigiCert's public
		 * certificate repository, so trusted-list endpoints that serve an **incomplete**
		 * TLS chain can still be validated.
		 *
		 * Some member-state endpoints (currently `eidas.gov.ie`) present only their leaf
		 * certificate, omitting the intermediate CA. Adding a *root* (as the bundled
		 * [TL_TRANSPORT_ROOT_RESOURCES] do) cannot bridge a missing *intermediate*;
		 * instead the JDK must fetch it from the leaf's Authority Information Access
		 * `caIssuers` URL. That fetching is off by default ([AIA_CA_ISSUERS_PROPERTY])
		 * and, on recent JDKs, additionally gated by a deny-all
		 * [ALLOWED_AIA_LOCATIONS_PROPERTY] filter — so both are set.
		 *
		 * The allowlist is pinned to [DIGICERT_AIA_REPOSITORY] alone: it confines
		 * fetches to one public CA repository (no SSRF surface from arbitrary AIA URLs
		 * in hostile certificates), and trust is unaffected regardless — a fetched
		 * intermediate must still chain to a trusted root, so a spoofed or compromised
		 * repository can at worst deny service, never forge trust. Fetching is a pure
		 * fallback: a complete chain never triggers it.
		 *
		 * Both properties are process-global and read by the JDK before the first TLS
		 * handshake, so call this once at host startup ahead of any network use. A value
		 * an operator has already set for either property is respected and left intact.
		 */
		fun enableAiaCaIssuerFetching() {
			if (System.getProperty(AIA_CA_ISSUERS_PROPERTY) == null) {
				System.setProperty(AIA_CA_ISSUERS_PROPERTY, "true")
			}
			if (System.getProperty(ALLOWED_AIA_LOCATIONS_PROPERTY) == null) {
				System.setProperty(ALLOWED_AIA_LOCATIONS_PROPERTY, DIGICERT_AIA_REPOSITORY)
			}
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
