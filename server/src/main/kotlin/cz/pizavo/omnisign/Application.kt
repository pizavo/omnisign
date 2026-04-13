package cz.pizavo.omnisign

import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerConfigLoader
import cz.pizavo.omnisign.data.service.Pkcs11WarmupService
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.di.serverModule
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.plugins.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import java.io.File
import java.security.KeyStore

private val logger = KotlinLogging.logger {}

/**
 * Server entry point.
 *
 * Loads [ServerConfig] from `server.yml` and starts a Netty embedded server.
 *
 * When TLS is configured and [ServerConfig.proxyMode] is `false`, a TLS connector is created
 * with TLS 1.2/1.3 and HTTP/2 ALPN negotiation enabled. To restrict to TLS 1.3 only, set the
 * JVM system property `-Djdk.tls.disabledAlgorithms=TLSv1,TLSv1.1,TLSv1.2` at launch.
 * Otherwise, a plain HTTP connector
 * is used (suitable for deployment behind a TLS-terminating reverse proxy).
 *
 * The `--config <path>` argument can be passed on the command line to point to a non-default
 * YAML config file location.
 */
fun main(args: Array<String>) {
	val configPath = args.indexOf("--config").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
	val serverConfig = ServerConfigLoader().load(configPath)

	System.setProperty("io.ktor.development", serverConfig.development.toString())
	if (serverConfig.development) {
		logger.info { "Development mode is ENABLED" }
	}

	val tlsCfg = serverConfig.tls?.takeUnless { serverConfig.proxyMode }

	if (tlsCfg != null) {
		val keyStore = loadKeyStore(tlsCfg.keystorePath, tlsCfg.keystorePassword)

		embeddedServer(
			Netty,
			environment = applicationEnvironment { },
			configure = {
				sslConnector(
					keyStore = keyStore,
					keyAlias = tlsCfg.keyAlias,
					keyStorePassword = { tlsCfg.keystorePassword.toCharArray() },
					privateKeyPassword = { tlsCfg.privateKeyPassword.toCharArray() },
				) {
					port = serverConfig.tlsPort
					host = serverConfig.host
				}
			},
		) {
			moduleWith(serverConfig)
		}.start(wait = true)

		logger.info { "TLS connector configured on ${serverConfig.host}:${serverConfig.tlsPort} (TLS 1.2/1.3, HTTP/2 ALPN)" }
	} else {
		if (serverConfig.proxyMode) {
			logger.info { "Proxy mode enabled — plain HTTP on ${serverConfig.host}:${serverConfig.port}" }
		} else {
			logger.info { "No TLS configured — plain HTTP on ${serverConfig.host}:${serverConfig.port}" }
		}

		embeddedServer(
			Netty,
			port = serverConfig.port,
			host = serverConfig.host,
		) {
			moduleWith(serverConfig)
		}.start(wait = true)
	}
}

/**
 * Configure the full application module with the given [ServerConfig].
 *
 * @param serverConfig Server configuration instance.
 */
fun Application.moduleWith(serverConfig: ServerConfig) {
	configureKoin(serverConfig)
	launchPkcs11WarmupIfNeeded(serverConfig)
	configureDefaultHeaders(hstsConfig = serverConfig.tls?.hsts)
	configureSerialization()
	configureStatusPages()
	configureCallId()
	configureCallLogging()
	configureCompression(serverConfig.compression)
	configureAutoHeadResponse()
	configureCors(serverConfig.cors, tlsEnabled = serverConfig.tls != null || serverConfig.proxyMode)
	configureForwardedHeaders(serverConfig.proxyMode)
	configureHttpsRedirect(serverConfig)
	configureRateLimiting(serverConfig.rateLimiting)

	val authConfig = serverConfig.auth
	val externalUrl = if (authConfig != null) resolveExternalUrl(serverConfig) else ""
	configureAuthentication(authConfig, externalUrl)
	configureRouting(authConfig, serverConfig.rateLimiting)

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

	logger.info { "Allowed operations: ${serverConfig.allowedOperations.joinToString { it.name }}" }

	if (AllowedOperation.SIGN in serverConfig.allowedOperations && authConfig?.enabled != true) {
		logger.warn {
			"⚠️  SIGN operation is enabled WITHOUT authentication — all configured signing " +
					"certificates are accessible to any network-reachable client. " +
					"Set auth.enabled: true or restrict access with allowedCertificateAliases."
		}
	}

	if (serverConfig.allowedCertificateAliases != null) {
		logger.info {
			"Certificate alias allowlist: ${serverConfig.allowedCertificateAliases.joinToString()}"
		}
	}
}

/**
 * Configure the full application module with default [ServerConfig].
 *
 * This overload is primarily used by `testApplication` and by the embedded server
 * when no external [ServerConfig] customization is required beyond the YAML file.
 *
 * @param serverConfig Server configuration; defaults to [ServerConfig] with built-in values.
 */
fun Application.module(serverConfig: ServerConfig = ServerConfig()) {
	moduleWith(serverConfig)
}

/**
 * Install Koin DI with shared and server-specific modules.
 */
fun Application.configureKoin(serverConfig: ServerConfig) {
	install(Koin) {
		modules(
			appModule,
			jvmRepositoryModule,
			serverModule(serverConfig),
		)
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
 * When `SIGN` is not in [ServerConfig.allowedOperations], warmup is skipped entirely
 * because the certificate discovery route (`GET /api/v1/certificates`) is gated behind
 * `SIGN` and will never be invoked.
 *
 * @param serverConfig Current server configuration.
 */
private fun Application.launchPkcs11WarmupIfNeeded(serverConfig: ServerConfig) {
	if (AllowedOperation.SIGN !in serverConfig.allowedOperations) {
		logger.debug { "SIGN operation not enabled — skipping PKCS#11 warmup" }
		return
	}

	val warmupService by inject<Pkcs11WarmupService>()
	val configRepo by inject<ConfigRepository>()

	CoroutineScope(Dispatchers.IO).launch {
		try {
			val config = configRepo.getCurrentConfig()
			val userLibs = config.global.customPkcs11Libraries.map { it.name to it.path }
			logger.info { "Launching PKCS#11 background warmup (${userLibs.size} user lib(s))" }
			warmupService.warmup(userPkcs11Libraries = userLibs)
		} catch (e: Exception) {
			logger.warn(e) { "PKCS#11 background warmup failed — certificate discovery will use subprocess probing" }
			val signal by inject<MutableStateFlow<Boolean>>()
			signal.value = true
		}
	}
}

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
 * variable first, falling back to constructing a URL from [ServerConfig.host] and the
 * active port/scheme.
 *
 * @param serverConfig Current server configuration.
 * @return Base URL string (no trailing slash).
 */
private fun resolveExternalUrl(serverConfig: ServerConfig): String {
	System.getenv("OMNISIGN_EXTERNAL_URL")?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }

	val scheme = if (serverConfig.tls != null && !serverConfig.proxyMode) "https" else "http"
	val port = if (serverConfig.tls != null && !serverConfig.proxyMode) serverConfig.tlsPort else serverConfig.port
	val host = serverConfig.host.let { if (it == "0.0.0.0") "localhost" else it }
	return "$scheme://$host:$port"
}

