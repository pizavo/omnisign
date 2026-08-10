package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.collectParts
import cz.pizavo.omnisign.api.deleteFileParts
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.extractTextField
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.model.responses.TimestampResultMeta
import cz.pizavo.omnisign.api.parseEnumSetField
import cz.pizavo.omnisign.api.requireOperation
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.config.OperationConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.GetDocumentTimestampInfoUseCase
import cz.pizavo.omnisign.plugins.serverJson
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Mount timestamping/extension API routes under `/api/v1/timestamp`.
 *
 * `POST /api/v1/timestamp` accepts a `multipart/form-data` request with:
 * - `file` — the signed PDF to extend (required).
 * - `targetLevel` — target PAdES level name (optional, defaults to `PADES_BASELINE_LTA`).
 * - `profile` — named configuration profile (optional). When omitted, global defaults apply.
 *   No server-side active profile is used as a fallback.
 * - `disableHashAlgorithm` — comma-separated list of [HashAlgorithm] names to additionally
 *   disable for this request (optional). Union-merged with the global and profile disabled
 *   sets via [OperationConfig]; the request is rejected with `422` if the resolved hash
 *   algorithm ends up in the disabled set. Strictly tightening — cannot re-enable an
 *   algorithm a higher layer has disabled.
 * - `disableEncryptionAlgorithm` — comma-separated list of [EncryptionAlgorithm] names to
 *   additionally disable for this request (optional). Same semantics as `disableHashAlgorithm`.
 *
 * The per-request override surface is intentionally limited to these two strictly-tightening
 * fields: the institution stands the service up to enforce *its* rules, so loosening fields
 * (alternate TSAs, custom validation policies) are not exposed even though they exist in
 * [OperationConfig]. The [OperationConfig] passed to [ResolvedConfig.resolve] is assembled
 * here with only these two fields populated, so a future expansion of the override pipeline
 * cannot accidentally widen the API surface.
 *
 * The TSA configuration is always taken from the server's pre-configured global or profile
 * settings. Clients cannot supply their own TSA credentials.
 *
 * On success the response is the extended PDF with `application/pdf` content type.
 * A `X-OmniSign-Result` header carries [TimestampResultMeta] as JSON.
 *
 * `POST /api/v1/timestamp/inspect` performs a lightweight pre-flight inspection of a signed
 * PDF and returns a [cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo] JSON body.
 * Clients use it to decide
 * which target PAdES levels are valid extensions for the document (e.g. disabling B-T when
 * the document is already at B-LTA, avoiding a no-op B-LT extension when LT-data is present)
 * without running a full validation. Accepts the same `file` multipart field as the main
 * route. Gated by the same [AllowedOperation.TIMESTAMP] permission since it only exists to
 * inform timestamping decisions.
 */
fun Route.timestampRoutes() {
	val extendUseCase by inject<ExtendDocumentUseCase>()
	val inspectUseCase by inject<GetDocumentTimestampInfoUseCase>()
	val configRepository by inject<ConfigRepository>()
	val serverConfig by inject<ServerConfig>()

	post("/api/v1/timestamp") {
		if (!call.requireOperation(AllowedOperation.TIMESTAMP, serverConfig)) return@post

		val multipart = call.receiveMultipart()
		val parts = multipart.collectParts(serverConfig.maxFileSize)

		try {
			val filePart = parts.filterIsInstance<FilePartData>().firstOrNull { it.name == "file" }
			if (filePart == null) {
				call.respond(
					HttpStatusCode.BadRequest,
					ApiError(error = "MISSING_FILE", message = "Multipart field 'file' is required"),
				)
				return@post
			}

			val disabledHashAlgorithms = call.parseEnumSetField(
				parts, "disableHashAlgorithm", HashAlgorithm.entries, "INVALID_ALGORITHM",
			) ?: return@post
			val disabledEncryptionAlgorithms = call.parseEnumSetField(
				parts, "disableEncryptionAlgorithm", EncryptionAlgorithm.entries, "INVALID_ALGORITHM",
			) ?: return@post

			val profileName = extractTextField(parts, "profile")
			val appConfig = configRepository.getCurrentConfig()
			val profileConfig = profileName?.let { appConfig.profiles[it] }

			val operationOverrides = OperationConfig(
				disabledHashAlgorithms = disabledHashAlgorithms,
				disabledEncryptionAlgorithms = disabledEncryptionAlgorithms,
			).takeIf { disabledHashAlgorithms.isNotEmpty() || disabledEncryptionAlgorithms.isNotEmpty() }

			val resolvedConfig = ResolvedConfig.resolve(appConfig.global, profileConfig, operationOverrides)
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
				inputBytes = filePart.file.readBytes(),
				inputName = filePart.originalFileName ?: filePart.file.name,
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
						annotatedWarnings = result.annotatedWarnings,
						revocationDataMissing = result.revocationDataMissing,
					)
					call.response.header("X-OmniSign-Result", serverJson.encodeToString(meta))
					call.respondBytes(result.outputBytes, ContentType.Application.Pdf)
				},
			)
		} finally {
			parts.deleteFileParts()
		}
	}

	post("/api/v1/timestamp/inspect") {
		if (!call.requireOperation(AllowedOperation.TIMESTAMP, serverConfig)) return@post

		val multipart = call.receiveMultipart()
		val parts = multipart.collectParts(serverConfig.maxFileSize)

		try {
			val filePart = parts.filterIsInstance<FilePartData>().firstOrNull { it.name == "file" }
			if (filePart == null) {
				call.respond(
					HttpStatusCode.BadRequest,
					ApiError(error = "MISSING_FILE", message = "Multipart field 'file' is required"),
				)
				return@post
			}

			inspectUseCase(filePart.file.readBytes()).fold(
				ifLeft = { error ->
					throw OperationException(error)
				},
				ifRight = { info ->
					call.respond(info)
				},
			)
		} finally {
			parts.deleteFileParts()
		}
	}
}




