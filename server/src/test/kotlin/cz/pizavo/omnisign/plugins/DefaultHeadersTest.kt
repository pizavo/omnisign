package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.HstsConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies the [configureDefaultHeaders] plugin adds expected response headers.
 */
class DefaultHeadersTest : FunSpec({

	fun ApplicationTestBuilder.configureTestApp(hstsConfig: HstsConfig? = null) {
		application {
			configureDefaultHeaders(hstsConfig = hstsConfig)
			routing { get("/test") { call.respondText("ok") } }
		}
	}

	test("X-Powered-By header is present in responses") {
		testApplication {
			configureTestApp()
			val response = client.get("/test")
			response.status shouldBe HttpStatusCode.OK
			response.headers["X-Powered-By"] shouldBe "OmniSign"
		}
	}

	test("X-Content-Type-Options nosniff header is present") {
		testApplication {
			configureTestApp()
			val response = client.get("/test")
			response.headers["X-Content-Type-Options"] shouldBe "nosniff"
		}
	}

	test("X-Frame-Options DENY header is present") {
		testApplication {
			configureTestApp()
			val response = client.get("/test")
			response.headers["X-Frame-Options"] shouldBe "DENY"
		}
	}

	test("Referrer-Policy header is present") {
		testApplication {
			configureTestApp()
			val response = client.get("/test")
			response.headers["Referrer-Policy"] shouldBe "strict-origin-when-cross-origin"
		}
	}

	test("Content-Security-Policy header restricts all sources") {
		testApplication {
			configureTestApp()
			val response = client.get("/test")
			response.headers["Content-Security-Policy"] shouldBe "default-src 'none'; frame-ancestors 'none'"
		}
	}

	test("Strict-Transport-Security is absent when hstsConfig is null") {
		testApplication {
			configureTestApp(hstsConfig = null)
			val response = client.get("/test")
			response.headers["Strict-Transport-Security"].shouldBeNull()
		}
	}

	test("Strict-Transport-Security uses maxAgeSeconds and includeSubDomains by default") {
		testApplication {
			configureTestApp(hstsConfig = HstsConfig())
			val response = client.get("/test")
			response.headers["Strict-Transport-Security"] shouldBe "max-age=31536000; includeSubDomains"
		}
	}

	test("Strict-Transport-Security omits includeSubDomains when disabled") {
		testApplication {
			configureTestApp(hstsConfig = HstsConfig(includeSubDomains = false))
			val response = client.get("/test")
			response.headers["Strict-Transport-Security"] shouldBe "max-age=31536000"
		}
	}

	test("Strict-Transport-Security appends preload directive when enabled") {
		testApplication {
			configureTestApp(hstsConfig = HstsConfig(preload = true))
			val response = client.get("/test")
			response.headers["Strict-Transport-Security"] shouldBe "max-age=31536000; includeSubDomains; preload"
		}
	}

	test("Strict-Transport-Security respects custom maxAgeSeconds") {
		testApplication {
			configureTestApp(hstsConfig = HstsConfig(maxAgeSeconds = 300))
			val response = client.get("/test")
			response.headers["Strict-Transport-Security"] shouldBe "max-age=300; includeSubDomains"
		}
	}
})
