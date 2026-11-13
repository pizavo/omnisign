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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.security.KeyStore
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

/**
 * Verifies that `POST /api/v1/sign` holds every request to [OperationsConfig.certificateAliases],
 * including a request that names no certificate alias.
 *
 * The server signs from an empty PKCS#12 keystore, so a request that gets past the allowlist fails
 * at key resolution with an error naming the alias the server looked for. That shows which alias
 * the server chose without signing anything or touching a real token.
 */
class SigningAliasAllowlistTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }
	val keystorePassword = "allowlist-test"
	val keystore = createTempFile("omnisign-allowlist-", ".p12").also { path ->
		path.outputStream().use { out ->
			KeyStore.getInstance("PKCS12").apply { load(null, null) }.store(out, keystorePassword.toCharArray())
		}
	}

	afterSpec { keystore.deleteIfExists() }

	/**
	 * Start a server that signs from the empty keystore with [certificateAliases] as its allowlist,
	 * send it one sign request naming [alias] (no alias when `null`), and return the status and
	 * error it answers with.
	 */
	fun signingError(certificateAliases: List<String>?, alias: String?): Pair<HttpStatusCode, ApiError> {
		lateinit var answer: Pair<HttpStatusCode, ApiError>
		testApplication {
			application {
				module(
					ServerConfig(
						listen = ListenConfig(host = "127.0.0.1"),
						operations = OperationsConfig(
							allowed = setOf(AllowedOperation.SIGN),
							certificateAliases = certificateAliases,
							signingKeystorePath = keystore.toString(),
						),
						cors = CorsConfig(allowedOrigins = listOf("*")),
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
							alias?.let { append("certificateAlias", it) }
						},
					),
				)
			}
			answer = response.status to json.decodeFromString<ApiError>(response.bodyAsText())
		}
		return answer
	}

	test("a request without certificateAlias signs with the only allowed alias") {
		val (status, error) = signingError(certificateAliases = listOf("university-seal"), alias = null)

		status shouldBe HttpStatusCode.ServiceUnavailable
		error.error shouldBe "TOKEN_ACCESS_ERROR"
		error.message shouldContain "'university-seal'"
	}

	test("a request without certificateAlias is rejected when several aliases are allowed") {
		val (status, error) = signingError(
			certificateAliases = listOf("university-seal", "department-stamp"),
			alias = null,
		)

		status shouldBe HttpStatusCode.BadRequest
		error.error shouldBe "MISSING_CERTIFICATE_ALIAS"
	}

	test("a request naming an allowed alias signs with that alias") {
		val (status, error) = signingError(
			certificateAliases = listOf("university-seal", "department-stamp"),
			alias = "department-stamp",
		)

		status shouldBe HttpStatusCode.ServiceUnavailable
		error.message shouldContain "'department-stamp'"
	}

	test("a request naming an alias outside the allowed list is rejected") {
		val (status, error) = signingError(certificateAliases = listOf("university-seal"), alias = "rector-personal")

		status shouldBe HttpStatusCode.Forbidden
		error.error shouldBe "CERTIFICATE_NOT_ALLOWED"
	}

	test("without an allowed list a request may leave certificateAlias out") {
		val (status, error) = signingError(certificateAliases = null, alias = null)

		status shouldBe HttpStatusCode.ServiceUnavailable
		error.message shouldBe "No suitable certificate found"
	}
})
