package cz.pizavo.omnisign.api.routes

import cz.pizavo.omnisign.api.collectParts
import cz.pizavo.omnisign.api.deleteFileParts
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.extractTextField
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.api.parseEnumSetField
import cz.pizavo.omnisign.api.preferredLanguageTag
import cz.pizavo.omnisign.api.requireOperation
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.config.OperationConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
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
 * - `profile` — named configuration profile to use (optional). When omitted, global
 *   defaults apply. No server-side active profile is used as a fallback.
 * - `formats` — comma-separated list of [RawReportFormat] names whose XML payloads should
 *   be marshalled into the response's `rawReports` map (optional, case-insensitive,
 *   whitespace-tolerant). Each requested format triggers a separate JAXB pass, so the field
 *   is opt-in: when omitted the response's `rawReports` map is empty. Unknown format names
 *   short-circuit the request with `400 INVALID_FORMAT` so a typo does not silently yield a
 *   partial set.
 * - `disableHashAlgorithm` — comma-separated list of [HashAlgorithm] names to additionally
 *   disable for this single request (optional). Union-merged with the global and profile
 *   disabled sets via [OperationConfig]; the request is rejected with `422` if the resolved
 *   algorithm ends up in the disabled set. Strictly tightening — cannot re-enable an
 *   algorithm a higher layer has disabled.
 * - `disableEncryptionAlgorithm` — comma-separated list of [EncryptionAlgorithm] names to
 *   additionally disable for this single request (optional). Same semantics as
 *   `disableHashAlgorithm`.
 *
 * The per-request override surface is intentionally limited to these two strictly-tightening
 * fields: the institution stands the service up to enforce *its* rules, so loosening fields
 * (custom validation policies, alternate TSAs, skipping globally-mandated trusted lists) are
 * not exposed even though they exist in [OperationConfig]. The [OperationConfig] passed to
 * [ResolvedConfig.resolve] is assembled here with only these two fields populated, so a
 * future expansion of the override pipeline cannot accidentally widen the API surface.
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

			val rawReportFormats = call.parseEnumSetField(
				parts, "formats", RawReportFormat.entries, "INVALID_FORMAT",
			) ?: return@post
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

			val parameters = ValidationParameters(
				inputBytes = filePart.file.readBytes(),
				inputName = filePart.originalFileName ?: filePart.file.name,
				resolvedConfig = resolvedConfig,
				rawReportFormats = rawReportFormats,
				language = call.preferredLanguageTag(),
			)

			validateUseCase(parameters).fold(
				ifLeft = { error ->
					throw OperationException(error)
				},
				ifRight = { report ->
					call.respond(report)
				},
			)
		} finally {
			parts.deleteFileParts()
		}
	}
}




