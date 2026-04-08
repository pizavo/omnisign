package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.CompressionConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

/**
 * Verifies the [configureCompression] plugin installs correctly and compresses responses.
 */
class CompressionTest : FunSpec({

	test("gzip compression is applied when Accept-Encoding includes gzip") {
		testApplication {
			application {
				configureCompression(CompressionConfig())
				routing {
					get("/test") {
						call.respondText("A".repeat(2048))
					}
				}
			}
			val response = client.get("/test") {
				header(HttpHeaders.AcceptEncoding, "gzip")
			}
			response.status shouldBe HttpStatusCode.OK
			response.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
		}
	}

	test("deflate compression is applied when only deflate is accepted") {
		testApplication {
			application {
				configureCompression(CompressionConfig())
				routing {
					get("/test") {
						call.respondText("B".repeat(2048))
					}
				}
			}
			val response = client.get("/test") {
				header(HttpHeaders.AcceptEncoding, "deflate")
			}
			response.status shouldBe HttpStatusCode.OK
			response.headers[HttpHeaders.ContentEncoding] shouldBe "deflate"
		}
	}

	test("compression is skipped when disabled via config") {
		testApplication {
			application {
				configureCompression(CompressionConfig(enabled = false))
				routing {
					get("/test") {
						call.respondText("C".repeat(2048))
					}
				}
			}
			val response = client.get("/test") {
				header(HttpHeaders.AcceptEncoding, "gzip")
			}
			response.status shouldBe HttpStatusCode.OK
			response.headers[HttpHeaders.ContentEncoding] shouldBe null
		}
	}

	test("small responses below minimumSize are not compressed") {
		testApplication {
			application {
				configureCompression(CompressionConfig(gzipMinimumSize = 4096))
				routing {
					get("/test") {
						call.respondText("D".repeat(100))
					}
				}
			}
			val response = client.get("/test") {
				header(HttpHeaders.AcceptEncoding, "gzip")
			}
			response.status shouldBe HttpStatusCode.OK
			response.headers[HttpHeaders.ContentEncoding] shouldBe null
		}
	}
})

