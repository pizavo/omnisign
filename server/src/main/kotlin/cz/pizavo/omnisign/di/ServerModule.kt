package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.auth.OidcDiscoveryService
import cz.pizavo.omnisign.auth.OidcUserInfoService
import cz.pizavo.omnisign.auth.ServerPasswordCallback
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerConfigLoader
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.platform.PasswordCallback
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Server-specific Koin module.
 *
 * Provides:
 * - [ServerConfig] singleton from the preloaded configuration.
 * - [ServerConfigLoader] singleton.
 * - [PasswordCallback] that always returns `null` (server cannot prompt interactively).
 * - IO [CoroutineContext] for blocking work.
 * - [HttpClient] (CIO engine) with JSON content-negotiation for OIDC discovery and
 *   user-info requests.
 * - [JwtSessionService] for issuing and verifying session tokens. The signing secret is
 *   resolved from `auth.session.secret` → `OMNISIGN_JWT_SECRET` env var → random UUID when
 *   auth is disabled → ephemeral dev secret (development mode only, logs a warning) →
 *   startup error (production with auth enabled but no secret configured).
 * - [OidcDiscoveryService] and [OidcUserInfoService] for the OIDC authorization-code flow.
 *
 * @param serverConfig Preloaded server configuration.
 */
fun serverModule(serverConfig: ServerConfig) = module {
	single<ServerConfig> { serverConfig }
	single<ServerConfigLoader> { ServerConfigLoader() }
	single<PasswordCallback> { ServerPasswordCallback() }
	single<CoroutineContext> { Dispatchers.IO }

	single<HttpClient> {
		HttpClient(CIO) {
			install(ContentNegotiation) {
				json(Json { ignoreUnknownKeys = true })
			}
		}
	}

	single<OidcDiscoveryService> { OidcDiscoveryService(get()) }
	single<OidcUserInfoService> { OidcUserInfoService(get()) }

	single<JwtSessionService> {
		val config = serverConfig.auth?.session ?: SessionConfig()
		val authEnabled = serverConfig.auth?.enabled == true
		val secret = config.secret
			?: System.getenv("OMNISIGN_JWT_SECRET")
			?: if (!authEnabled) {
				@OptIn(ExperimentalUuidApi::class)
				Uuid.generateV7().toString()
			} else if (serverConfig.development) {
				"dev-secret-not-for-production-use".also {
					logger.warn(
						"⚠️  JWT secret is not configured — using an ephemeral development secret. " +
								"All tokens will be invalidated on server restart. " +
								"Set the OMNISIGN_JWT_SECRET environment variable before deploying to production.",
					)
				}
			} else {
				error(
					"JWT secret is not configured. Set 'auth.session.secret' in server.yml " +
							"or provide the OMNISIGN_JWT_SECRET environment variable.",
				)
			}
		JwtSessionService(config.copy(secret = secret))
	}
}

