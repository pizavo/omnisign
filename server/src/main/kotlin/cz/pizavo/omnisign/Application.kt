package cz.pizavo.omnisign

import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerConfigLoader
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SigningConfigLoader
import cz.pizavo.omnisign.config.TrustReconciler
import cz.pizavo.omnisign.config.isLoopbackHost
import cz.pizavo.omnisign.config.unsatisfiableSigningTargets
import cz.pizavo.omnisign.config.validateAuthConfig
import cz.pizavo.omnisign.config.validateCorsConfig
import cz.pizavo.omnisign.config.validateOperationsConfig
import cz.pizavo.omnisign.config.validateProxyConfig
import cz.pizavo.omnisign.config.validateTransportSecurity
import cz.pizavo.omnisign.data.service.PcscMonitorService
import cz.pizavo.omnisign.data.service.Pkcs11CacheInvalidator
import cz.pizavo.omnisign.data.service.Pkcs11WarmupService
import cz.pizavo.omnisign.auth.HandoffCodeStore
import cz.pizavo.omnisign.auth.LoginRequestStore
import cz.pizavo.omnisign.auth.PkceVerifierStore
import cz.pizavo.omnisign.auth.RefreshTokenStore
import cz.pizavo.omnisign.data.service.TrustedListRefreshScheduler
import cz.pizavo.omnisign.data.service.pkcs11DropDir
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.di.serverModule
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.plugins.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.engine.HttpClientEngine
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.io.File
import java.nio.file.Path
import java.security.KeyStore

private val logger = KotlinLogging.logger {}

/**
 * Server entry point.
 *
 * Loads [ServerConfig] from `server.yml` and starts a Netty embedded server.
 *
 * When TLS is configured and reverse-proxy mode is inactive (`proxy` absent or
 * `proxy.enabled: false`), a TLS connector is created with TLS 1.2/1.3 and HTTP/2 ALPN
 * negotiation enabled. To restrict to TLS 1.3 only, set the JVM system property
 * `-Djdk.tls.disabledAlgorithms=TLSv1,TLSv1.1,TLSv1.2` at launch. Otherwise, a plain HTTP
 * connector is used (suitable for deployment behind a TLS-terminating reverse proxy).
 *
 * The `--config <path>` argument can be passed on the command line to point to a non-default
 * YAML config file location.
 */
fun main(args: Array<String>) {
	cz.pizavo.omnisign.data.repository.DssServiceFactory.enableAiaCaIssuerFetching()

	val configPath = args.indexOf("--config").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
	val serverConfig = ServerConfigLoader().load(configPath)
	val secrets = ServerSecrets.resolveFromEnv(serverConfig)

	if (serverConfig.development && !isLoopbackHost(serverConfig.listen.host)) {
		error(
			"Refusing to start: development mode (development: true) is incompatible with a " +
					"non-loopback host '${serverConfig.listen.host}'. Development mode enables verbose " +
					"error pages and other behaviour that exposes internal details to anyone who " +
					"can reach the port. Either bind to a loopback address (127.0.0.1 or ::1) or " +
					"set development: false in server.yml.",
		)
	}

	System.setProperty("io.ktor.development", serverConfig.development.toString())
	if (serverConfig.development) {
		logger.info { "Development mode is ENABLED" }
	}

	val tlsCfg = serverConfig.tls?.takeUnless { serverConfig.proxy?.enabled == true }

	if (tlsCfg != null) {
		val keystorePassword = checkNotNull(secrets.tlsKeystorePassword) {
			"TLS is configured but tlsKeystorePassword was not resolved — this is a programming " +
					"error in ServerSecrets.resolveFromEnv (env var should already be required)."
		}
		val privateKeyPassword = secrets.tlsPrivateKeyPassword ?: keystorePassword
		val keyStore = loadKeyStore(tlsCfg.keystorePath, keystorePassword.value)

		embeddedServer(
			Netty,
			environment = applicationEnvironment { },
			configure = {
				sslConnector(
					keyStore = keyStore,
					keyAlias = tlsCfg.keyAlias,
					keyStorePassword = { keystorePassword.value.toCharArray() },
					privateKeyPassword = { privateKeyPassword.value.toCharArray() },
				) {
					port = tlsCfg.port
					host = serverConfig.listen.host
				}
			},
		) {
			moduleWith(serverConfig, secrets)
		}.start(wait = true)

		logger.info { "TLS connector configured on ${serverConfig.listen.host}:${tlsCfg.port} (TLS 1.2/1.3, HTTP/2 ALPN)" }
	} else {
		if (serverConfig.proxy?.enabled == true) {
			logger.info { "Proxy mode enabled — plain HTTP on ${serverConfig.listen.host}:${serverConfig.listen.port}" }
		} else {
			logger.info { "No TLS configured — plain HTTP on ${serverConfig.listen.host}:${serverConfig.listen.port}" }
		}

		embeddedServer(
			Netty,
			port = serverConfig.listen.port,
			host = serverConfig.listen.host,
		) {
			moduleWith(serverConfig, secrets)
		}.start(wait = true)
	}
}

/**
 * Configure the full application module with the given [ServerConfig] and resolved
 * [ServerSecrets].
 *
 * @param serverConfig Server configuration instance.
 * @param secrets Secret values resolved from environment variables. Tests inject an
 *   explicit instance; production callers obtain it from [ServerSecrets.resolveFromEnv].
 * @param httpClientEngine Test-only outbound-HTTP engine override (a `MockEngine` IdP), threaded to
 *   [configureKoin]; `null` in production.
 */
fun Application.moduleWith(serverConfig: ServerConfig, secrets: ServerSecrets, httpClientEngine: HttpClientEngine? = null) {
	validateAuthConfig(serverConfig.auth)
	val parsedProxy = validateProxyConfig(serverConfig.proxy)
	val corsConfig = validateCorsConfig(serverConfig.cors)
	validateTransportSecurity(serverConfig)
	validateOperationsConfig(serverConfig.operations)
	serverConfig.operations.signingKeystorePath?.let { path ->
		require(File(path).isFile) {
			"operations.signingKeystorePath '$path' does not exist or is not a regular file. " +
				"Point it at the server's PKCS#12 signing keystore."
		}
		logger.info { "Signing key source: file keystore at $path" }
	}
	val signingConfig = SigningConfigLoader().load(serverConfig.signingConfigFile)
	configureKoin(serverConfig, secrets, signingConfig, httpClientEngine)
	reconcileTrustIfConfigured(serverConfig, signingConfig)
	if (backgroundServicesEnabled()) {
		launchPkcs11WarmupIfNeeded(serverConfig)
		launchTrustedListRefreshIfNeeded(serverConfig)
		attachPkcs11CacheInvalidatorIfNeeded(serverConfig)
		launchSessionStorePruneIfNeeded(serverConfig.auth)
	}
	configureDefaultHeaders(hstsConfig = serverConfig.tls?.hsts)
	configureSerialization()
	configureStatusPages(development = serverConfig.development)
	configureCallId()
	configureCallLogging()
	configureAutoHeadResponse()
	configureCors(corsConfig, tlsEnabled = serverConfig.tls != null || parsedProxy.enabled)
	configureForwardedHeaders(parsedProxy)
	configureHttpsRedirect(serverConfig)
	configureRateLimiting(serverConfig.rateLimiting)

	val authConfig = serverConfig.auth
	val externalUrl = if (authConfig != null) resolveExternalUrl(serverConfig) else ""
	val secureCookies = serverConfig.tls != null || parsedProxy.enabled
	configureAuthentication(authConfig, externalUrl)
	configureRouting(authConfig, serverConfig.rateLimiting, secureCookies)

	if (authConfig?.enabled == true) {
		if (authConfig.providers.isEmpty()) {
			logger.warn {
				"⚠️  auth.enabled is true but no auth providers are configured — all API calls will be rejected with 401"
			}
		} else {
			logger.info {
				"Authentication ENABLED — providers: ${authConfig.providers.joinToString { it.name }}"
			}
		}
	}

	logger.info { "Allowed operations: ${serverConfig.operations.allowed.joinToString { it.name }}" }

	if (AllowedOperation.SIGN in serverConfig.operations.allowed && authConfig?.enabled != true) {
		logger.warn {
			"⚠️  SIGN operation is enabled WITHOUT authentication — all configured signing " +
					"certificates are accessible to any network-reachable client. " +
					"Set auth.enabled: true or restrict access with operations.certificateAliases."
		}
	}

	val unsatisfiableTargets = unsatisfiableSigningTargets(serverConfig.operations, signingConfig)
	if (unsatisfiableTargets.isNotEmpty()) {
		logger.warn {
			"⚠️  SIGN is enabled but TIMESTAMP is not — the following signing targets require an " +
					"RFC 3161 timestamp and will reject every sign request with TIMESTAMP_NOT_ALLOWED: " +
					"${unsatisfiableTargets.joinToString()}. Enable the TIMESTAMP operation, or lower " +
					"their signature level to PADES_BASELINE_B."
		}
	}

	if (serverConfig.operations.certificateAliases != null) {
		logger.info {
			"Certificate alias allowlist: ${serverConfig.operations.certificateAliases.joinToString()}"
		}
	}
}

/**
 * Whether the background warmup/refresh services should start at boot.
 *
 * Production defaults to enabled. Tests pass `-Domnisign.backgroundServices=off` so the suite
 * never reaches out to live trusted-list (LOTL) endpoints: those fetches are slow and
 * network-flaky, and the blocking downloads they spawn do not honor coroutine cancellation, so
 * across hundreds of `testApplication` boots they accumulate and exhaust the test JVM heap.
 *
 * @return `true` unless the `omnisign.backgroundServices` system property is set to `off`.
 */
private fun backgroundServicesEnabled(): Boolean =
	System.getProperty("omnisign.backgroundServices", "on") != "off"

/**
 * Configure the full application module with default [ServerConfig].
 *
 * This overload is primarily used by `testApplication` and by the embedded server
 * when no external [ServerConfig] customization is required beyond the YAML file.
 *
 * @param serverConfig Server configuration; defaults to [ServerConfig] with built-in values.
 * @param secrets Resolved env-var secrets. Defaults to [ServerSecrets.resolveFromEnv]; tests
 *   typically supply an explicit instance with literal test values so they do not need to
 *   set process-wide env vars before each run.
 * @param httpClientEngine Test-only override for the server's outbound HTTP-client engine, letting a
 *   test substitute a `MockEngine` for the IdP so the OIDC callback's token-exchange → UserInfo hop
 *   runs end-to-end; `null` (production) uses CIO.
 */
fun Application.module(
	serverConfig: ServerConfig = ServerConfig(),
	secrets: ServerSecrets = ServerSecrets.resolveFromEnv(serverConfig),
	httpClientEngine: HttpClientEngine? = null,
) {
	moduleWith(serverConfig, secrets, httpClientEngine)
}

/**
 * Install Koin DI with shared and server-specific modules. [httpClientEngine], when non-null,
 * overrides the outbound HTTP client's engine so tests can inject a `MockEngine` IdP.
 */
fun Application.configureKoin(serverConfig: ServerConfig, secrets: ServerSecrets, signingConfig: AppConfig, httpClientEngine: HttpClientEngine? = null) {
	install(Koin) {
		modules(
			appModule,
			jvmRepositoryModule,
			serverModule(serverConfig, secrets, signingConfig, httpClientEngine),
		)
	}
}

/**
 * Reconcile the server's trust directory from the `signing.yml` `trustedCertificates` references,
 * synchronously at boot so requests never see a partially-provisioned trust set.
 *
 * Skipped when no signing policy file is configured — there is no declarative trust to provision,
 * and the trust directory is left untouched. A fatal reconcile condition (integrity mismatch,
 * unresolvable reference, malformed entry) throws out of [TrustReconciler.reconcile], aborting
 * startup.
 *
 * @param serverConfig Current server configuration.
 * @param signingConfig Provider signing/validation policy resolved from signing.yml.
 */
private fun Application.reconcileTrustIfConfigured(serverConfig: ServerConfig, signingConfig: AppConfig) {
	val signingFile = serverConfig.signingConfigFile ?: return
	val baseDir = File(signingFile).absoluteFile.parentFile?.toPath() ?: Path.of(".").toAbsolutePath()
	val reconciler: TrustReconciler = get()
	runBlocking { reconciler.reconcile(signingConfig, baseDir) }
}

/**
 * Attach the PKCS#11 cache invalidator when [AllowedOperation.SIGN] is enabled.
 *
 * Resolves [Pkcs11CacheInvalidator] from Koin so its lazy `single` definition is
 * instantiated, which in turn starts its background subscription to
 * [cz.pizavo.omnisign.data.service.PcscMonitorService.events].  The invalidator
 * clears the discoverer's caches in response to card insertion / removal and
 * reader (un)plug events, ensuring the next certificate-discovery call sees
 * fresh hardware state without waiting for the cache TTL to elapse.
 *
 * Skipped when `SIGN` is not allowed because PKCS#11 discovery is gated behind it
 * and the cache would never be consulted anyway.
 *
 * @param serverConfig Current server configuration.
 */
private fun Application.attachPkcs11CacheInvalidatorIfNeeded(serverConfig: ServerConfig) {
	if (AllowedOperation.SIGN !in serverConfig.operations.allowed) {
		logger.debug { "SIGN operation not enabled — skipping PKCS#11 cache invalidator" }
		return
	}

	val invalidator: Pkcs11CacheInvalidator = get()
	val pcscMonitor: PcscMonitorService = get()
	logger.debug { "PKCS#11 cache invalidator attached (${invalidator::class.simpleName})" }

	monitor.subscribe(ApplicationStopping) {
		invalidator.close()
		pcscMonitor.close()
	}
}

/**
 * Launch a background PKCS#11 warmup cycle when [AllowedOperation.SIGN] is enabled.
 *
 * Signing requires PKCS#11 token discovery to list available certificates.  The warmup
 * cycle pre-initializes PKCS#11 middleware libraries in-process so that subsequent
 * certificate discovery calls use the fast in-process path rather than spawning
 * unreliable subprocesses.
 *
 * When `SIGN` is not in [OperationsConfig.allowed], warmup is skipped entirely
 * because the certificate discovery route (`GET /api/v1/certificates`) is gated behind
 * `SIGN` and will never be invoked.
 *
 * @param serverConfig Current server configuration.
 */
private fun Application.launchPkcs11WarmupIfNeeded(serverConfig: ServerConfig) {
	if (AllowedOperation.SIGN !in serverConfig.operations.allowed) {
		logger.debug { "SIGN operation not enabled — skipping PKCS#11 warmup" }
		return
	}

	val warmupService: Pkcs11WarmupService = get()
	val configRepo: ConfigRepository = get()
	val signal: MutableStateFlow<Boolean> = get()

	launch(Dispatchers.IO) {
		try {
			val config = configRepo.getCurrentConfig()
			val userLibs = config.global.customPkcs11Libraries.map { it.name to it.path }
			val pkcs11Dir = pkcs11DropDir()
			logger.info { "Launching PKCS#11 background warmup (${userLibs.size} user lib(s), dropDir=$pkcs11Dir)" }
			warmupService.warmup(appDataPkcs11Dir = pkcs11Dir, userPkcs11Libraries = userLibs)
		} catch (e: Exception) {
			logger.warn(e) { "PKCS#11 background warmup failed — certificate discovery will use subprocess probing" }
			signal.value = true
		}
	}
}

/**
 * Launch the process-global trusted-list warmup and refresh cycle on a background
 * coroutine, so the EU LOTL is parsed once at boot and refreshed coherently on the
 * configured interval rather than on a validation/signing request's critical path.
 *
 * Skipped when neither `VALIDATE` nor `SIGN` is allowed (a `TIMESTAMP`-only server
 * never builds a trust chain), mirroring [launchPkcs11WarmupIfNeeded]'s gating.
 *
 * @param serverConfig Current server configuration.
 */
private fun Application.launchTrustedListRefreshIfNeeded(serverConfig: ServerConfig) {
	val needsTrust = AllowedOperation.VALIDATE in serverConfig.operations.allowed ||
			AllowedOperation.SIGN in serverConfig.operations.allowed
	if (!needsTrust) {
		logger.debug { "Neither VALIDATE nor SIGN enabled — skipping trusted-list refresh cycle" }
		return
	}

	val scheduler: TrustedListRefreshScheduler = get()
	launch(Dispatchers.IO) {
		try {
			logger.info { "Launching trusted-list background warmup and refresh cycle" }
			scheduler.run()
		} catch (e: Exception) {
			logger.warn(e) { "Trusted-list refresh cycle stopped — validation will fall back to offline-first loading" }
		}
	}
}

/**
 * Launch a background coroutine that periodically deletes expired rows from the four session
 * stores (refresh tokens, hand-off codes, in-flight login requests, PKCE verifiers).
 *
 * Expiry is already enforced lazily — every store's `consume` rejects a row whose timestamp
 * has passed — so this cycle is about disk, not correctness: without it, abandoned flows and
 * sessions that expire without an explicit logout would accumulate rows forever over a long
 * uptime. It prunes once at boot (draining whatever the previous run left behind across a
 * deploy) and then on [SESSION_STORE_PRUNE_INTERVAL].
 *
 * Skipped entirely when [authConfig] is `null`: with no auth configured no session row is ever
 * written, and resolving the stores here would defeat their laziness by creating the SQLite
 * file on a deployment that never needs it. Also skipped in tests, which run with background
 * services off (see [backgroundServicesEnabled]).
 *
 * @param authConfig Root authentication configuration, or `null` when auth is disabled.
 */
private fun Application.launchSessionStorePruneIfNeeded(authConfig: cz.pizavo.omnisign.config.AuthConfig?) {
	if (authConfig == null) {
		logger.debug { "Auth not configured — skipping session-store prune cycle" }
		return
	}

	val refreshTokenStore: RefreshTokenStore = get()
	val pkceVerifierStore: PkceVerifierStore = get()
	val handoffCodeStore: HandoffCodeStore = get()
	val loginRequestStore: LoginRequestStore = get()

	launch(Dispatchers.IO) {
		while (isActive) {
			try {
				val pruned = refreshTokenStore.pruneExpired() +
						handoffCodeStore.pruneExpired() +
						loginRequestStore.pruneExpired() +
						pkceVerifierStore.pruneExpired()
				if (pruned > 0) {
					logger.debug { "Session-store prune removed $pruned expired row(s)" }
				}
			} catch (e: Exception) {
				logger.warn(e) { "Session-store prune cycle failed — expired rows will be retried next cycle" }
			}
			delay(SESSION_STORE_PRUNE_INTERVAL)
		}
	}
}

/**
 * How often the session-store prune cycle runs after its initial boot-time sweep.
 *
 * One hour — the rows it removes are already inert (expired rows can never be consumed), so the
 * only thing a longer interval costs is a little disk between sweeps, and a shorter one would
 * add wake-ups that buy nothing. An hour keeps the four small session tables from drifting far
 * past their live working set on a long-running server.
 */
private val SESSION_STORE_PRUNE_INTERVAL = 1.hours

/**
 * Load a JKS or PKCS#12 keystore from the filesystem.
 *
 * @param path Absolute path to the keystore file.
 * @param password Keystore password.
 * @return Loaded [KeyStore].
 */
private fun loadKeyStore(path: String, password: String): KeyStore {
	val file = File(path)
	require(file.isFile) { "Keystore file not found: $path" }

	val type = if (path.endsWith(".p12") || path.endsWith(".pfx")) "PKCS12" else "JKS"
	val keyStore = KeyStore.getInstance(type)
	file.inputStream().use { keyStore.load(it, password.toCharArray()) }
	return keyStore
}

/**
 * Derive the externally reachable base URL for the server.
 *
 * Used to build OAuth2 redirect URIs. Reads the `OMNISIGN_EXTERNAL_URL` environment
 * variable first, falling back to constructing a URL from [ListenConfig.host] and the
 * active port/scheme.
 *
 * @param serverConfig Current server configuration.
 * @return Base URL string (no trailing slash).
 */
private fun resolveExternalUrl(serverConfig: ServerConfig): String {
	System.getenv("OMNISIGN_EXTERNAL_URL")?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }

	val proxyEnabled = serverConfig.proxy?.enabled == true
	val tlsActive = serverConfig.tls != null && !proxyEnabled
	val scheme = if (tlsActive) "https" else "http"
	val port = if (tlsActive) serverConfig.tls.port else serverConfig.listen.port
	val host = serverConfig.listen.host.let { if (it == "0.0.0.0") "localhost" else it }
	return "$scheme://$host:$port"
}

