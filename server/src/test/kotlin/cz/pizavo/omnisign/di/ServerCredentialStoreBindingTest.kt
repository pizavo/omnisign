package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.auth.EmptyCredentialStore
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.data.service.KeyringCredentialStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.core.Koin
import org.koin.dsl.koinApplication

/**
 * Verifies which [CredentialStore] the server's DI graph hands out, depending on whether
 * [AllowedOperation.SIGN] is allowed.
 *
 * Only a signing server has a use for the host's keychain — it is where the PIN of a
 * PIN-protected token is looked up — so every other server must get [EmptyCredentialStore]
 * instead of the keyring-backed store `jvmRepositoryModule` binds.
 */
class ServerCredentialStoreBindingTest : FunSpec({

	val secrets = ServerSecrets(
		jwtSecret = null,
		tlsKeystorePassword = null,
		tlsPrivateKeyPassword = null,
		signingKeystorePassword = null,
		oidcClientSecrets = emptyMap(),
	)

	/**
	 * Start the graph the server installs at boot for [allowed] operations, hand it to [block],
	 * and close it afterwards.
	 */
	fun withServerGraph(allowed: Set<AllowedOperation>, block: (Koin) -> Unit) {
		val serverConfig = ServerConfig(
			listen = ListenConfig(host = "127.0.0.1"),
			operations = OperationsConfig(allowed = allowed),
			cors = CorsConfig(allowedOrigins = listOf("*")),
		)
		val application = koinApplication {
			modules(
				appModule,
				jvmRepositoryModule,
				serverModule(serverConfig, secrets, AppConfig(global = GlobalConfig())),
			)
		}
		try {
			block(application.koin)
		} finally {
			application.close()
		}
	}

	test("a server that only validates never opens the host's keychain") {
		withServerGraph(setOf(AllowedOperation.VALIDATE)) { koin ->
			koin.get<CredentialStore>().shouldBeInstanceOf<EmptyCredentialStore>()
		}
	}

	test("a server that only timestamps never opens the host's keychain") {
		withServerGraph(setOf(AllowedOperation.TIMESTAMP)) { koin ->
			koin.get<CredentialStore>().shouldBeInstanceOf<EmptyCredentialStore>()
		}
	}

	test("a signing server keeps the keyring store, where a token's PIN is looked up") {
		withServerGraph(setOf(AllowedOperation.VALIDATE, AllowedOperation.SIGN)) { koin ->
			koin.get<CredentialStore>().shouldBeInstanceOf<KeyringCredentialStore>()
		}
	}
})
