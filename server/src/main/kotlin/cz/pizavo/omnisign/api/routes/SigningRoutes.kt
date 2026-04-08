package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.collectParts
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.extractFilePart
import cz.pizavo.omnisign.api.extractTextField
import cz.pizavo.omnisign.api.model.ApiError
import cz.pizavo.omnisign.api.model.SigningResultMeta
import cz.pizavo.omnisign.api.requireOperation
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
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
 * Mount signing API routes under `/api/v1/sign`.
 *
 * `POST /api/v1/sign` accepts a `multipart/form-data` request with:
 * - `file` — the PDF to sign (required).
 * - `certificateAlias` — alias of the certificate to use (optional).
 * - `hashAlgorithm` — hash algorithm name (optional, e.g. `SHA256`).
 * - `signatureLevel` — PAdES level (optional, e.g. `PADES_BASELINE_T`).
 * - `reason`, `location`, `contactInfo` — optional signature metadata.
 * - `noTimestamp` — set to `true` to omit the RFC 3161 timestamp.
 * - `profile` — named configuration profile to use (optional).
 *
 * This operation is disabled by default and must be explicitly enabled in [ServerConfig.allowedOperations].
 * When [ServerConfig.allowedCertificateAliases] is set, only those aliases may be used.
 *
 * On success the response is the signed PDF with `application/pdf` content type.
 * A `X-OmniSign-Result` header carries [SigningResultMeta] as JSON.
 */
fun Route.signingRoutes() {
	val signUseCase by inject<SignDocumentUseCase>()
	val configRepository by inject<ConfigRepository>()
	val serverConfig by inject<ServerConfig>()

	post("/api/v1/sign") {
		if (!call.requireOperation(AllowedOperation.SIGN, serverConfig)) return@post

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

		val outputFile = withContext(Dispatchers.IO) {
			File.createTempFile("omnisign-signed-", ".pdf")
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

			val hashAlgorithm = extractTextField(parts, "hashAlgorithm")
				?.let { name -> HashAlgorithm.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }

			val signatureLevel = extractTextField(parts, "signatureLevel")
				?.let { name -> SignatureLevel.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }

			val noTimestamp = extractTextField(parts, "noTimestamp")?.toBoolean() == true

			val requestedAlias = extractTextField(parts, "certificateAlias")
			val allowedAliases = serverConfig.allowedCertificateAliases
			if (allowedAliases != null && requestedAlias != null && requestedAlias !in allowedAliases) {
				call.respond(
					HttpStatusCode.Forbidden,
					ApiError(
						error = "CERTIFICATE_NOT_ALLOWED",
						message = "Certificate alias '$requestedAlias' is not in the server's allowed list",
					),
				)
				return@post
			}

			val parameters = SigningParameters(
				inputFile = inputFile.absolutePath,
				outputFile = outputFile.absolutePath,
				certificateAlias = requestedAlias,
				hashAlgorithm = hashAlgorithm,
				signatureLevel = signatureLevel,
				reason = extractTextField(parts, "reason"),
				location = extractTextField(parts, "location"),
				contactInfo = extractTextField(parts, "contactInfo"),
				addTimestamp = !noTimestamp,
				resolvedConfig = resolvedConfig,
			)

			signUseCase(parameters).fold(
				ifLeft = { error ->
					throw OperationException(error)
				},
				ifRight = { result ->
					val meta = SigningResultMeta(
						signatureId = result.signatureId,
						signatureLevel = result.signatureLevel,
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






