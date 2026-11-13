package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.config.AiaConfig
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.data.repository.AiaFetchPolicy
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.koin.dsl.koinApplication

/**
 * Verifies which [AiaFetchPolicy] the server's DI graph hands to the DSS service factory.
 *
 * The desktop app and the CLI let AIA downloads reach internal addresses. The server must not by
 * default, because the URLs come from the documents anyone can send it, so its module overrides the
 * `jvmRepositoryModule` binding from the `aia` block of `server.yml`.
 */
class ServerAiaFetchPolicyBindingTest : FunSpec({

	val secrets = ServerSecrets(
		jwtSecret = null,
		tlsKeystorePassword = null,
		tlsPrivateKeyPassword = null,
		signingKeystorePassword = null,
		oidcClientSecrets = emptyMap(),
	)

	/** The [AiaFetchPolicy] of the graph the server installs at boot for [aia]. */
	fun serverPolicy(aia: AiaConfig): AiaFetchPolicy {
		val serverConfig = ServerConfig(
			listen = ListenConfig(host = "127.0.0.1"),
			cors = CorsConfig(allowedOrigins = listOf("*")),
			aia = aia,
		)
		val application = koinApplication {
			modules(
				appModule,
				jvmRepositoryModule,
				serverModule(serverConfig, secrets, AppConfig(global = GlobalConfig())),
			)
		}
		try {
			return application.koin.get()
		} finally {
			application.close()
		}
	}

	test("the desktop and CLI graph lets AIA downloads reach internal addresses") {
		val application = koinApplication { modules(appModule, jvmRepositoryModule) }
		try {
			application.koin.get<AiaFetchPolicy>().allowPrivateAddresses.shouldBeTrue()
		} finally {
			application.close()
		}
	}

	test("the server blocks internal addresses for AIA downloads by default") {
		serverPolicy(AiaConfig()).allowPrivateAddresses.shouldBeFalse()
	}

	test("aia.allowPrivateAddresses lets the server reach them") {
		serverPolicy(AiaConfig(allowPrivateAddresses = true)).allowPrivateAddresses.shouldBeTrue()
	}
})
