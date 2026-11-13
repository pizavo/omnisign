package cz.pizavo.omnisign.api.routes

import arrow.core.right
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.model.validation.ValidationResult
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.future.await
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse

/**
 * Verifies that a document operation does not hold the engine's call thread while it runs.
 *
 * The test runs the server on Netty with a single call thread, so a validation that blocked that
 * thread, as DSS does while it waits on a slow revocation server, would leave every other request
 * unanswered until it finished. The validation here blocks until the health check has answered.
 */
class DocumentOperationCallThreadTest : FunSpec({

	test("on Netty with one call thread, the health check answers while a validation blocks") {
		val validating = CountDownLatch(1)
		val release = CountDownLatch(1)
		val blockingRepository = object : ValidationRepository {
			override suspend fun validateDocument(parameters: ValidationParameters): OperationResult<ValidationReport> {
				validating.countDown()
				release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
				return ValidationReport(
					documentName = parameters.inputName,
					validationTime = Instant.parse("2026-01-01T00:00:00Z"),
					overallResult = ValidationResult.NO_SIGNATURES,
					signatures = emptyList(),
				).right()
			}
		}
		val server = embeddedServer(Netty, configure = {
			connector {
				host = "127.0.0.1"
				port = 0
			}
			callGroupSize = 1
		}) {
			module(
				ServerConfig(
					listen = ListenConfig(host = "127.0.0.1"),
					operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)),
					cors = CorsConfig(allowedOrigins = listOf("*")),
				),
			)
			getKoin().loadModules(listOf(module { single { ValidateDocumentUseCase(blockingRepository) } }))
		}.start(wait = false)
		try {
			val port = server.engine.resolvedConnectors().first().port
			JdkHttpClient.newBuilder().version(JdkHttpClient.Version.HTTP_1_1).build().use { client ->
				val validation = client.sendAsync(validationRequest(port), JdkHttpResponse.BodyHandlers.ofString())
				validating.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

				val health = JdkHttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/health"))
					.timeout(Duration.ofSeconds(HEALTH_TIMEOUT_SECONDS))
					.build()
				client.send(health, JdkHttpResponse.BodyHandlers.ofString()).statusCode() shouldBe HttpStatusCode.OK.value

				release.countDown()
				validation.await().statusCode() shouldBe HttpStatusCode.OK.value
			}
		} finally {
			release.countDown()
			server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
		}
	}
}) {
	companion object {

		/** How long the test waits for the validation to start or to be released. */
		private const val TIMEOUT_SECONDS = 30L

		/** How long the health check may take, while the validation blocks, before the test fails. */
		private const val HEALTH_TIMEOUT_SECONDS = 5L

		/** Boundary of the multipart validation request. */
		private const val BOUNDARY = "omnisign-call-thread-test"

		/** A `POST /api/v1/validate` request to the server on [port], carrying a small file. */
		fun validationRequest(port: Int): JdkHttpRequest {
			val body = buildString {
				append("--$BOUNDARY\r\n")
				append("Content-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"\r\n")
				append("Content-Type: application/pdf\r\n\r\n")
				append("%PDF-1.7\r\n")
				append("--$BOUNDARY--\r\n")
			}
			return JdkHttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/validate"))
				.timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
				.header(HttpHeaders.ContentType, "multipart/form-data; boundary=$BOUNDARY")
				.POST(JdkHttpRequest.BodyPublishers.ofString(body))
				.build()
		}
	}
}
