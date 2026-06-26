package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.exception.MultipleFilePartsException
import cz.pizavo.omnisign.api.exception.OperationException
import cz.pizavo.omnisign.api.model.responses.ApiError
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
 *
 * In addition to exception handlers, a status-code handler for [HttpStatusCode.TooManyRequests]
 * ensures that responses produced by the [io.ktor.server.plugins.ratelimit.RateLimit] plugin
 * also carry a JSON [ApiError] body consistent with the rest of the API. The standard
 * `Retry-After` and `X-RateLimit-*` headers set by the rate limiter are preserved.
 *
 * @param development When `true` the catch-all [Throwable] handler echoes `cause.message`
 *   into the response body's `details` field, easing local debugging. When `false`
 *   (production / non-development deployments) the field is omitted to avoid leaking
 *   internal exception messages (JVM stack frames, file paths, library internals, etc.)
 *   to clients. The unhandled exception is always logged in full server-side at ERROR
 *   level regardless of this flag.
 */
fun Application.configureStatusPages(development: Boolean = false) {
	install(StatusPages) {
		status(HttpStatusCode.TooManyRequests) { call, status ->
			call.respond(
				status,
				ApiError(
					error = "RATE_LIMIT_EXCEEDED",
					message = "Too many requests — please slow down and try again later.",
				),
			)
		}
		// ...existing exception handlers...
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
		exception<MultipleFilePartsException> { call, cause ->
			logger.warn { "Rejected multipart request with multiple file parts" }
			call.respond(
				HttpStatusCode.BadRequest,
				ApiError(
					error = "TOO_MANY_FILES",
					message = cause.message ?: "Exactly one file part is expected",
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
					details = if (development) cause.message else null,
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
		is SigningError.EncryptedDocument -> HttpStatusCode.UnprocessableEntity to "ENCRYPTED_DOCUMENT"
		is SigningError.MalformedDocument -> HttpStatusCode.BadRequest to "MALFORMED_DOCUMENT"

		is ValidationError.InvalidDocument -> HttpStatusCode.BadRequest to "INVALID_DOCUMENT"
		is ValidationError.InvalidPolicy -> HttpStatusCode.BadRequest to "INVALID_POLICY"
		is ValidationError.ValidationFailed -> HttpStatusCode.InternalServerError to "VALIDATION_FAILED"

		is ArchivingError.RevocationInfoError -> HttpStatusCode.BadGateway to "REVOCATION_INFO_ERROR"
		is ArchivingError.ExtensionFailed -> HttpStatusCode.InternalServerError to "EXTENSION_FAILED"
		is ArchivingError.TimestampFailed -> HttpStatusCode.BadGateway to "TIMESTAMP_FAILED"
		is ArchivingError.RenewalStatusUndeterminable -> HttpStatusCode.UnprocessableEntity to "RENEWAL_STATUS_UNDETERMINABLE"
		is ArchivingError.EncryptedDocument -> HttpStatusCode.UnprocessableEntity to "ENCRYPTED_DOCUMENT"
		is ArchivingError.MalformedDocument -> HttpStatusCode.BadRequest to "MALFORMED_DOCUMENT"

		is ConfigurationError.LoadFailed -> HttpStatusCode.InternalServerError to "CONFIG_LOAD_FAILED"
		is ConfigurationError.SaveFailed -> HttpStatusCode.InternalServerError to "CONFIG_SAVE_FAILED"
		is ConfigurationError.InvalidConfiguration -> HttpStatusCode.UnprocessableEntity to "INVALID_CONFIGURATION"

		is TrustStoreError.NotFound -> HttpStatusCode.NotFound to "TRUST_CERT_NOT_FOUND"
		is TrustStoreError.ParseFailed -> HttpStatusCode.BadRequest to "INVALID_CERTIFICATE"
		is TrustStoreError.StorageFailed -> HttpStatusCode.InternalServerError to "TRUST_STORE_FAILED"
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


