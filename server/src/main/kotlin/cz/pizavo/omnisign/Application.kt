package cz.pizavo.omnisign

import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerConfigLoader
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.di.serverModule
import cz.pizavo.omnisign.plugins.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
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
 * with TLS v1.3 enforced and HTTP/2 ALPN negotiation enabled. Otherwise, a plain HTTP connector
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

		logger.info { "TLS connector configured on ${serverConfig.host}:${serverConfig.tlsPort} (TLS v1.3, HTTP/2)" }
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
	configureDefaultHeaders()
	configureSerialization()
	configureStatusPages()
	configureCallLogging()
	configureCompression(serverConfig.compression)
	configureAutoHeadResponse()
	configureCors(serverConfig.cors, tlsEnabled = serverConfig.tls != null || serverConfig.proxyMode)
	configureForwardedHeaders(serverConfig.proxyMode)
	configureHttpsRedirect(serverConfig)
	configureRouting()

	if (serverConfig.requireLogin) {
		logger.info { "Login requirement is ENABLED (authentication mechanism not yet implemented)" }
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
