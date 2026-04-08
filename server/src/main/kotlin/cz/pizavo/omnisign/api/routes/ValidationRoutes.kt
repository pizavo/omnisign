package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.collectParts
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.extractFilePart
import cz.pizavo.omnisign.api.extractTextField
import cz.pizavo.omnisign.api.model.ApiError
import cz.pizavo.omnisign.api.requireOperation
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.validation.json.toJsonReport
import cz.pizavo.omnisign.domain.model.validation.json.toJsonString
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ValidateDocumentUseCase
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount validation API routes under `/api/v1/validate`.
 *
 * `POST /api/v1/validate` accepts a `multipart/form-data` request with:
 * - `file` — the PDF to validate (required).
 * - `profile` — named configuration profile to use (optional).
 *
 * On success the response is a JSON validation report.
 */
fun Route.validationRoutes() {
	val validateUseCase by inject<ValidateDocumentUseCase>()
	val configRepository by inject<ConfigRepository>()
	val serverConfig by inject<ServerConfig>()

	post("/api/v1/validate") {
		if (!call.requireOperation(AllowedOperation.VALIDATE, serverConfig)) return@post

		val multipart = call.receiveMultipart()
		val parts = multipart.collectParts()

		val inputFile = extractFilePart(parts, "file", serverConfig.maxFileSize)
		if (inputFile == null) {
			call.respond(
				HttpStatusCode.BadRequest,
				ApiError(error = "MISSING_FILE", message = "Multipart field 'file' is required"),
			)
			return@post
		}

		try {
			val profileName = extractTextField(parts, "profile")
			val appConfig = configRepository.getCurrentConfig()
			val activeProfile = profileName ?: appConfig.activeProfile
			val profileConfig = activeProfile?.let { appConfig.profiles[it] }

			val resolvedConfig = ResolvedConfig.resolve(appConfig.global, profileConfig, null)
				.fold(
					ifLeft = { error ->
						call.respond(
							HttpStatusCode.UnprocessableEntity,
							ApiError(error = "INVALID_CONFIGURATION", message = error.message),
						)
						return@post
					},
					ifRight = { it },
				)

			val parameters = ValidationParameters(
				inputFile = inputFile.absolutePath,
				resolvedConfig = resolvedConfig,
			)

			validateUseCase(parameters).fold(
				ifLeft = { error ->
					throw OperationException(error)
				},
				ifRight = { report ->
					val jsonReport = report.toJsonReport().toJsonString()
					call.respondText(jsonReport, ContentType.Application.Json)
				},
			)
		} finally {
			inputFile.delete()
		}
	}
}




