package cz.pizavo.omnisign.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * A [MockEngine]-backed [HttpClient] carrying the same content negotiation and
 * success-expectation that [cz.pizavo.omnisign.di.webDataModule] gives the real one, so a spec
 * exercises the production configuration rather than a convenient approximation of it.
 *
 * @param expectSuccess Whether a non-2xx response surfaces as a `ResponseException`. `true`
 *   mirrors the session-carrying API client every `Remote*Repository` receives; pass `false` for
 *   the bare client behind [cz.pizavo.omnisign.web.auth.WebAuthApi], which reads a `401` as an
 *   expected value rather than an exception.
 * @param handler Answers each request the spec issues.
 */
fun mockApiClient(
	expectSuccess: Boolean = true,
	handler: MockRequestHandler,
): HttpClient = HttpClient(MockEngine) {
	this.expectSuccess = expectSuccess
	install(ContentNegotiation) {
		json(
			Json {
				ignoreUnknownKeys = true
				isLenient = false
			},
		)
	}
	engine { addHandler(handler) }
}
