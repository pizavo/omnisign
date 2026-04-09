package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.collectParts
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.extractFilePart
import cz.pizavo.omnisign.api.extractTextField
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.model.responses.TimestampResultMeta
import cz.pizavo.omnisign.api.requireOperation
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
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
 * Mount timestamping/extension API routes under `/api/v1/timestamp`.
 *
 * `POST /api/v1/timestamp` accepts a `multipart/form-data` request with:
 * - `file` — the signed PDF to extend (required).
 * - `targetLevel` — target PAdES level name (optional, defaults to `PADES_BASELINE_LTA`).
 * - `profile` — named configuration profile (optional). When omitted, global defaults apply.
 *   No server-side active profile is used as a fallback.
 *
 * The TSA configuration is always taken from the server's pre-configured global or profile
 * settings. Clients cannot supply their own TSA credentials.
 *
 * On success the response is the extended PDF with `application/pdf` content type.
 * A `X-OmniSign-Result` header carries [TimestampResultMeta] as JSON.
 */
fun Route.timestampRoutes() {
	val extendUseCase by inject<ExtendDocumentUseCase>()
	val configRepository by inject<ConfigRepository>()
	val serverConfig by inject<ServerConfig>()

	post("/api/v1/timestamp") {
		if (!call.requireOperation(AllowedOperation.TIMESTAMP, serverConfig)) return@post

		val multipart = call.receiveMultipart()
		val parts = multipart.collectParts(serverConfig.maxFileSize)

		val inputFile = extractFilePart(parts, "file", serverConfig.maxFileSize)
		if (inputFile == null) {
			call.respond(
				HttpStatusCode.BadRequest,
				ApiError(error = "MISSING_FILE", message = "Multipart field 'file' is required"),
			)
			return@post
		}

		val outputFile = withContext(Dispatchers.IO) {
			File.createTempFile("omnisign-timestamped-", ".pdf")
		}
		outputFile.deleteOnExit()

		try {
			val profileName = extractTextField(parts, "profile")
			val appConfig = configRepository.getCurrentConfig()
			val profileConfig = profileName?.let { appConfig.profiles[it] }

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
					val meta = TimestampResultMeta(
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




