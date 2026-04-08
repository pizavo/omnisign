package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.model.ApiError
import cz.pizavo.omnisign.domain.model.error.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

private val logger = KotlinLogging.logger {}

/**
 * Install Ktor [StatusPages] plugin that maps domain [OperationError] subtypes and
 * common exceptions to structured JSON error responses with appropriate HTTP status codes.
 */
fun Application.configureStatusPages() {
	install(StatusPages) {
		exception<OperationException> { call, cause ->
			call.respondOperationError(cause.operationError)
		}
		exception<FileTooLargeException> { call, cause ->
			logger.warn { "File too large: ${cause.message}" }
			call.respond(
				HttpStatusCode.PayloadTooLarge,
				ApiError(
					error = "FILE_TOO_LARGE",
					message = cause.message ?: "Uploaded file exceeds the maximum allowed size",
				),
			)
		}
		exception<IllegalArgumentException> { call, cause ->
			logger.warn(cause) { "Bad request: ${cause.message}" }
			call.respond(
				HttpStatusCode.BadRequest,
				ApiError(
					error = "BAD_REQUEST",
					message = cause.message ?: "Invalid request",
				),
			)
		}
		exception<Throwable> { call, cause ->
			logger.error(cause) { "Unhandled exception" }
			call.respond(
				HttpStatusCode.InternalServerError,
				ApiError(
					error = "INTERNAL_ERROR",
					message = "An unexpected error occurred",
					details = cause.message,
				),
			)
		}
	}
}

/**
 * Map a domain [OperationError] to the appropriate HTTP status code and [ApiError] body.
 */
private suspend fun ApplicationCall.respondOperationError(error: OperationError) {
	val (status, errorType) = when (error) {
		is SigningError.InvalidParameters -> HttpStatusCode.BadRequest to "INVALID_PARAMETERS"
		is SigningError.TokenAccessError -> HttpStatusCode.ServiceUnavailable to "TOKEN_ACCESS_ERROR"
		is SigningError.TimestampError -> HttpStatusCode.BadGateway to "TIMESTAMP_ERROR"
		is SigningError.ExpiredAlgorithm -> HttpStatusCode.UnprocessableEntity to "EXPIRED_ALGORITHM"
		is SigningError.SigningFailed -> HttpStatusCode.InternalServerError to "SIGNING_FAILED"

		is ValidationError.InvalidDocument -> HttpStatusCode.BadRequest to "INVALID_DOCUMENT"
		is ValidationError.InvalidPolicy -> HttpStatusCode.BadRequest to "INVALID_POLICY"
		is ValidationError.ValidationFailed -> HttpStatusCode.InternalServerError to "VALIDATION_FAILED"

		is ArchivingError.RevocationInfoError -> HttpStatusCode.BadGateway to "REVOCATION_INFO_ERROR"
		is ArchivingError.ExtensionFailed -> HttpStatusCode.InternalServerError to "EXTENSION_FAILED"
		is ArchivingError.TimestampFailed -> HttpStatusCode.BadGateway to "TIMESTAMP_FAILED"

		is ConfigurationError.LoadFailed -> HttpStatusCode.InternalServerError to "CONFIG_LOAD_FAILED"
		is ConfigurationError.SaveFailed -> HttpStatusCode.InternalServerError to "CONFIG_SAVE_FAILED"
		is ConfigurationError.InvalidConfiguration -> HttpStatusCode.UnprocessableEntity to "INVALID_CONFIGURATION"
	}

	logger.warn { "$errorType: ${error.message}" }
	respond(
		status,
		ApiError(
			error = errorType,
			message = error.message,
			details = error.details,
		),
	)
}


