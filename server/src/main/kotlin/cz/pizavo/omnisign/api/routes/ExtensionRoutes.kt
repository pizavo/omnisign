package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.OperationException
import cz.pizavo.omnisign.api.collectParts
import cz.pizavo.omnisign.api.extractFilePart
import cz.pizavo.omnisign.api.extractTextField
import cz.pizavo.omnisign.api.model.ApiError
import cz.pizavo.omnisign.api.model.ExtensionResultMeta
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.plugins.serverJson
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.ktor.ext.inject
import java.io.File

/**
 * Mount extension/archiving API routes under `/api/v1/extend`.
 *
 * `POST /api/v1/extend` accepts a `multipart/form-data` request with:
 * - `file` — the signed PDF to extend (required).
 * - `targetLevel` — target PAdES level name (optional, defaults to `PADES_BASELINE_LTA`).
 * - `profile` — named configuration profile (optional).
 *
 * On success the response is the extended PDF with `application/pdf` content type.
 * A `X-OmniSign-Result` header carries [ExtensionResultMeta] as JSON.
 */
fun Route.extensionRoutes() {
	val extendUseCase by inject<ExtendDocumentUseCase>()
	val configRepository by inject<ConfigRepository>()

	post("/api/v1/extend") {
		val multipart = call.receiveMultipart()
		val parts = multipart.collectParts()

		val inputFile = extractFilePart(parts, "file")
		if (inputFile == null) {
			call.respond(
				HttpStatusCode.BadRequest,
				ApiError(error = "MISSING_FILE", message = "Multipart field 'file' is required"),
			)
			return@post
		}

		val outputFile = withContext(Dispatchers.IO) {
			File.createTempFile("omnisign-extended-", ".pdf")
		}
		outputFile.deleteOnExit()

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

			val targetLevel = extractTextField(parts, "targetLevel")
				?.let { name -> SignatureLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
				?: SignatureLevel.PADES_BASELINE_LTA

			val parameters = ArchivingParameters(
				inputFile = inputFile.absolutePath,
				outputFile = outputFile.absolutePath,
				targetLevel = targetLevel,
				resolvedConfig = resolvedConfig,
			)

			extendUseCase(parameters).fold(
				ifLeft = { error ->
					throw OperationException(error)
				},
				ifRight = { result ->
					val meta = ExtensionResultMeta(
						newLevel = result.newSignatureLevel,
						warnings = result.warnings,
					)
					call.response.header("X-OmniSign-Result", serverJson.encodeToString(meta))
					call.respondFile(outputFile)
				},
			)
		} finally {
			inputFile.delete()
			outputFile.delete()
		}
	}
}




