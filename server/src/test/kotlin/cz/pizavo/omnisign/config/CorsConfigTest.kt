package cz.pizavo.omnisign.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [CorsConfig.allowedHosts], the one reading of `cors.allowedOrigins` that both the validator and the CORS
 * plugin use: each entry trimmed and without its `http://` or `https://` prefix, whatever the prefix's case.
 */
class CorsConfigTest : FunSpec({

	test("allowedHosts strips an http or https prefix in any case and trims each entry") {
		val config = CorsConfig(
			allowedOrigins = listOf(
				"https://app.example.org",
				"HTTP://localhost:8080",
				"Https://api.example.org:8443",
				"  sign.example.org  ",
				"example.com:18443",
			),
		)
		config.allowedHosts() shouldBe listOf(
			"app.example.org",
			"localhost:8080",
			"api.example.org:8443",
			"sign.example.org",
			"example.com:18443",
		)
	}

	test("allowedHosts reads the wildcard written as a URL as the host that allows any origin") {
		val entries = listOf("*", "https://*", "http://*", "HTTPS://*", " * ")
		CorsConfig(allowedOrigins = entries).allowedHosts() shouldBe List(entries.size) { CorsConfig.ANY_HOST }
	}

	test("allowedHosts keeps a prefix of another scheme and a subdomain wildcard as written") {
		val entries = listOf("ftp://files.example.org", "*.example.org")
		CorsConfig(allowedOrigins = entries).allowedHosts() shouldBe entries
	}
})
