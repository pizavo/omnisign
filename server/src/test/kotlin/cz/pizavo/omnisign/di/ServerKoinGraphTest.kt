package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import io.kotest.core.spec.style.FunSpec
import io.ktor.client.engine.HttpClientEngine
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.Verification

/**
 * Verifies the full DI graph the server installs at boot — `appModule + jvmRepositoryModule +
 * serverModule` — declares a home for every dependency it asks for.
 *
 * The server is the entry point where a missing binding costs the most: Koin resolves lazily, so a
 * definition nobody satisfies survives startup and only throws when the first request reaches the
 * route that needs it. This spec makes the whole graph answer for itself before a deployment does.
 *
 * Unlike the desktop and CLI graphs, the server needs no [cz.pizavo.omnisign.platform.PasswordCallback]
 * extra type: [serverModule] binds its own non-interactive one.
 *
 * The whitelist covers the parameters Koin's reflection-based verifier cannot tell apart from
 * injection points. It inspects each definition's constructor and assumes every parameter comes from
 * the graph, but [serverModule] hands several of them literal values closed over from its own
 * arguments — [Sensitive] and [Map] reach `ServerSecrets`, [SessionConfig] reaches `JwtSessionService`,
 * and [HttpClientEngine] reaches the outbound `HttpClient` — so each would otherwise be reported
 * missing. Whitelisting them is safe precisely because nothing declares them: no definition could
 * resolve one from the graph even if it asked.
 *
 * Note what this therefore does *not* reach. A definition written as `single<SomeInterface> { Impl() }`
 * has the interface as its primary type, and an interface has no constructor to reflect over, so the
 * verifier skips it. Most of [serverModule] is written that way; what this spec adds over
 * [cz.pizavo.omnisign.di.JvmKoinGraphTest] is the handful of concrete-typed definitions
 * (`TrustReconciler`, the OIDC services, `PkceService`, `JwtSessionService`) plus Koin's own
 * duplicate-definition detection across the three merged modules.
 *
 * @see cz.pizavo.omnisign.di.JvmKoinGraphTest for why the verifications are merged rather than run
 *   through Koin's `verifyAll`.
 */
@OptIn(KoinExperimentalAPI::class)
class ServerKoinGraphTest : FunSpec({

	val serverConfig = ServerConfig(
		listen = ListenConfig(host = "127.0.0.1"),
		operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)),
		cors = CorsConfig(allowedOrigins = listOf("*")),
	)

	val secrets = ServerSecrets(
		jwtSecret = null,
		tlsKeystorePassword = null,
		tlsPrivateKeyPassword = null,
		signingKeystorePassword = null,
		oidcClientSecrets = emptyMap(),
	)

	val extraTypes = listOf(
		MutableStateFlow::class,
		File::class,
		Path::class,
		List::class,
		Sensitive::class,
		Map::class,
		HttpClientEngine::class,
		SessionConfig::class,
	)

	test("the server graph resolves every dependency it declares") {
		val graph = Verification(appModule, extraTypes) +
			Verification(jvmRepositoryModule, extraTypes) +
			Verification(
				serverModule(
					serverConfig = serverConfig,
					secrets = secrets,
					signingConfig = AppConfig(global = GlobalConfig()),
				),
				extraTypes,
			)

		graph.verify()
	}
})
