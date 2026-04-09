package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.model.responses.GlobalConfigResponse
import cz.pizavo.omnisign.api.model.responses.ProfileConfigResponse
import cz.pizavo.omnisign.api.model.responses.ResolvedConfigResponse
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Integration tests for the config read-only routes added by [configRoutes].
 */
class ConfigRoutesTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	test("GET /api/v1/config/global returns 200 with a valid global config response") {
		testApplication {
			application { module(ServerConfig()) }
			val response = client.get("/api/v1/config/global")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<GlobalConfigResponse>(response.bodyAsText())
			HashAlgorithm.entries.contains(body.defaultHashAlgorithm) shouldBe true
			SignatureLevel.entries.contains(body.defaultSignatureLevel) shouldBe true
		}
	}

	test("GET /api/v1/config/profiles returns 200 with a valid profile list") {
		testApplication {
			application { module(ServerConfig()) }
			val response = client.get("/api/v1/config/profiles")
			response.status shouldBe HttpStatusCode.OK
			json.decodeFromString<List<ProfileConfigResponse>>(response.bodyAsText())
		}
	}

	test("GET /api/v1/config/profiles/{name} returns 404 for unknown profile") {
		testApplication {
			application { module(ServerConfig()) }
			val response = client.get("/api/v1/config/profiles/nonexistent")
			response.status shouldBe HttpStatusCode.NotFound
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "PROFILE_NOT_FOUND"
		}
	}

	test("GET /api/v1/config/resolved without profile param returns a valid resolved config") {
		testApplication {
			application { module(ServerConfig()) }
			val response = client.get("/api/v1/config/resolved")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<ResolvedConfigResponse>(response.bodyAsText())
			body.resolvedProfileName.shouldBeNull()
			HashAlgorithm.entries.contains(body.hashAlgorithm) shouldBe true
			SignatureLevel.entries.contains(body.signatureLevel) shouldBe true
		}
	}

	test("GET /api/v1/config/resolved with unknown profile returns 404") {
		testApplication {
			application { module(ServerConfig()) }
			val response = client.get("/api/v1/config/resolved?profile=ghost")
			response.status shouldBe HttpStatusCode.NotFound
			val body = json.decodeFromString<ApiError>(response.bodyAsText())
			body.error shouldBe "PROFILE_NOT_FOUND"
		}
	}

	test("GET /api/v1/config/resolved does not fall back to stored activeProfile") {
		testApplication {
			application { module(ServerConfig()) }
			val resolvedResponse = client.get("/api/v1/config/resolved")
			resolvedResponse.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<ResolvedConfigResponse>(resolvedResponse.bodyAsText())
			body.resolvedProfileName.shouldBeNull()
		}
	}

	test("GET /api/v1/config/profiles returns sorted list") {
		testApplication {
			application { module(ServerConfig()) }
			val response = client.get("/api/v1/config/profiles")
			response.status shouldBe HttpStatusCode.OK
			val body = json.decodeFromString<List<ProfileConfigResponse>>(response.bodyAsText())
			val names = body.map { it.name }
			names shouldBe names.sorted()
		}
	}
})




