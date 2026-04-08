package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.auth.ServerPasswordCallback
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerConfigLoader
import cz.pizavo.omnisign.platform.PasswordCallback
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext

/**
 * Server-specific Koin module.
 *
 * Provides the [ServerConfig] singleton, a [ServerConfigLoader] singleton,
 * a [PasswordCallback] that returns `null` (server cannot prompt interactively),
 * and an IO [CoroutineContext] for blocking work.
 *
 * @param serverConfig Pre-loaded server configuration.
 */
fun serverModule(serverConfig: ServerConfig) = module {
	single<ServerConfig> { serverConfig }
	single<ServerConfigLoader> { ServerConfigLoader() }
	single<PasswordCallback> { ServerPasswordCallback() }
	single<CoroutineContext> { Dispatchers.IO }
}

