package cz.pizavo.omnisign.api.routes

import arrow.core.right
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.CorsConfig
import cz.pizavo.omnisign.config.ListenConfig
import cz.pizavo.omnisign.config.OperationsConfig
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.GetDocumentTimestampInfoUseCase
import cz.pizavo.omnisign.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin

/**
 * Verifies that `POST /api/v1/timestamp/inspect` judges a document's level under the same
 * configuration `POST /api/v1/timestamp` would extend it with: the profile the request names, or
 * the global configuration when it names none.
 */
class TimestampInspectProfileTest : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	val serverConfig = ServerConfig(
		listen = ListenConfig(host = "127.0.0.1"),
		operations = OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE, AllowedOperation.TIMESTAMP)),
		cors = CorsConfig(allowedOrigins = listOf("*")),
	)

	val signingConfig = AppConfig(
		global = GlobalConfig(),
		profiles = mapOf(
			"corp" to ProfileConfig(name = "corp"),
			"broken" to ProfileConfig(
				name = "broken",
				hashAlgorithm = HashAlgorithm.SHA256,
				disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
			),
		),
		activeProfile = "corp",
	)

	/**
	 * Run the server with an inspection over [archivingRepository] and [signingConfig], post a
	 * small file to the inspect route with [profile] as its `profile` field when given, and hand
	 * the response to [check].
	 */
	fun inspect(
		archivingRepository: ArchivingRepository,
		profile: String?,
		check: suspend (HttpResponse) -> Unit,
	) = testApplication {
		application {
			module(serverConfig)
			getKoin().loadModules(
				listOf(
					module {
						single<ConfigRepository> { mockk { coEvery { getCurrentConfig() } returns signingConfig } }
						single { GetDocumentTimestampInfoUseCase(archivingRepository) }
					},
				),
			)
		}
		val response = client.post("/api/v1/timestamp/inspect") {
			setBody(
				MultiPartFormDataContent(
					formData {
						append("file", ByteArray(64), Headers.build {
							append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"")
							append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
						})
						profile?.let { append("profile", it) }
					},
				),
			)
		}
		check(response)
	}

	fun inspectingRepository(): ArchivingRepository = mockk {
		coEvery { getDocumentTimestampInfo(any(), any()) } returns
			DocumentTimestampInfo(hasDocumentTimestamp = false, containsLtData = false).right()
	}

	test("the inspection judges the level under the profile the request names") {
		val archivingRepository = inspectingRepository()

		inspect(archivingRepository, profile = "corp") { response ->
			response.status shouldBe HttpStatusCode.OK
		}

		coVerify(exactly = 1) {
			archivingRepository.getDocumentTimestampInfo(any(), match { it.profileName == "corp" })
		}
	}

	test("without a profile the inspection judges under the global configuration, as the extension does") {
		val archivingRepository = inspectingRepository()

		inspect(archivingRepository, profile = null) { response ->
			response.status shouldBe HttpStatusCode.OK
		}

		coVerify(exactly = 1) {
			archivingRepository.getDocumentTimestampInfo(any(), match { it.profileName == null })
		}
	}

	test("a profile whose configuration does not resolve is refused before anything is inspected") {
		val archivingRepository = inspectingRepository()

		inspect(archivingRepository, profile = "broken") { response ->
			response.status shouldBe HttpStatusCode.UnprocessableEntity
			json.decodeFromString<ApiError>(response.bodyAsText()).error shouldBe "INVALID_CONFIGURATION"
		}

		coVerify(exactly = 0) { archivingRepository.getDocumentTimestampInfo(any(), any()) }
	}
})
