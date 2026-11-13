package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.security.KeyStore
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

/**
 * Verifies that `POST /api/v1/sign` holds the `hashAlgorithm` a request asks for to the hash
 * algorithms that `signing.yml` or the request itself disables, as the CLI does for `--hash`.
 *
 * The server signs from an empty PKCS#12 keystore, so a request whose algorithm is accepted fails
 * later, at key resolution. No test signs anything or touches a real token.
 */
class SigningHashAlgorithmTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }
	val keystorePassword = "hash-test"
	val workDir = tempdir().toPath()
	val keystore = workDir.resolve("signing.p12").also { path ->
		path.outputStream().use { out ->
			KeyStore.getInstance("PKCS12").apply { load(null, null) }.store(out, keystorePassword.toCharArray())
		}
	}
	val signingConfig = workDir.resolve("signing.yml").also { path ->
		path.writeText(
			"""
			global:
			  disabledHashAlgorithms: [SHA3_256]
			""".trimIndent(),
		)
	}

	/**
	 * Start a server whose `signing.yml` disables SHA3-256, send it one sign request asking for
	 * [hashAlgorithm] and additionally disabling [disableHashAlgorithm] (nothing when `null`), and
	 * return the status and error it answers with.
	 */
	fun signingError(hashAlgorithm: String, disableHashAlgorithm: String? = null): Pair<HttpStatusCode, ApiError> {
		lateinit var answer: Pair<HttpStatusCode, ApiError>
		testApplication {
			application {
				module(
					ServerConfig(
						listen = ListenConfig(host = "127.0.0.1"),
						operations = OperationsConfig(
							allowed = setOf(AllowedOperation.SIGN),
							signingKeystorePath = keystore.toString(),
						),
						cors = CorsConfig(allowedOrigins = listOf("*")),
						signingConfigFile = signingConfig.toString(),
					),
					ServerSecrets(
						jwtSecret = null,
						tlsKeystorePassword = null,
						tlsPrivateKeyPassword = null,
						signingKeystorePassword = Sensitive(keystorePassword),
						oidcClientSecrets = emptyMap(),
					),
				)
			}
			val response = client.post("/api/v1/sign") {
				setBody(
					MultiPartFormDataContent(
						formData {
							append("file", "%PDF-1.7\n".encodeToByteArray(), Headers.build {
								append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"")
								append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
							})
							append("signatureLevel", "PADES_BASELINE_B")
							append("noTimestamp", "true")
							append("hashAlgorithm", hashAlgorithm)
							disableHashAlgorithm?.let { append("disableHashAlgorithm", it) }
						},
					),
				)
			}
			answer = response.status to json.decodeFromString<ApiError>(response.bodyAsText())
		}
		return answer
	}

	test("a hashAlgorithm that signing.yml disables is rejected") {
		val (status, error) = signingError(hashAlgorithm = "SHA3_256")

		status shouldBe HttpStatusCode.UnprocessableEntity
		error.error shouldBe "INVALID_CONFIGURATION"
		error.message shouldContain "SHA3_256"
	}

	test("a hashAlgorithm that the request itself disables is rejected") {
		val (status, error) = signingError(hashAlgorithm = "SHA512", disableHashAlgorithm = "SHA512")

		status shouldBe HttpStatusCode.UnprocessableEntity
		error.error shouldBe "INVALID_CONFIGURATION"
		error.message shouldContain "SHA512"
	}

	test("a hashAlgorithm that nothing disables gets past the check") {
		val (status, error) = signingError(hashAlgorithm = "SHA384")

		status shouldBe HttpStatusCode.ServiceUnavailable
		error.error shouldBe "TOKEN_ACCESS_ERROR"
	}
})
