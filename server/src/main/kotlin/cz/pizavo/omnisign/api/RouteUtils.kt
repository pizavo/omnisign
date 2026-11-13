package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.exception.FormFieldsTooLargeException
import cz.pizavo.omnisign.api.exception.MalformedMultipartException
import cz.pizavo.omnisign.api.exception.MultipleFilePartsException
import cz.pizavo.omnisign.api.exception.TooManyPartsException
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.FormFieldData
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import io.ktor.http.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Room a request body has beyond the file-size limit, for the form fields, part headers and
 * boundaries that come with the file. See [requestBodyLimit].
 *
 * It is also the most that the form fields and the headers of all parts of one request may take
 * together, since the server holds them in memory (see [MultipartFormReader]).
 */
private const val FORM_FIELDS_ALLOWANCE: Long = 1024L * 1024

/**
 * The most parts a multipart request may carry, the file included. The largest request the API
 * takes, a signing request, has eleven: the file and ten form fields.
 */
const val MAX_MULTIPART_PARTS = 32

/**
 * The largest request body the server accepts when a file may be up to [maxFileSize] bytes:
 * [maxFileSize] plus [FORM_FIELDS_ALLOWANCE] for the form fields that come with the file.
 *
 * The server holds every request body to it (see
 * [configureRequestBodyLimit][cz.pizavo.omnisign.plugins.configureRequestBodyLimit]).
 */
fun requestBodyLimit(maxFileSize: Long): Long =
	maxFileSize.coerceAtMost(Long.MAX_VALUE - FORM_FIELDS_ALLOWANCE) + FORM_FIELDS_ALLOWANCE

/**
 * Read the request's multipart body with a [MultipartFormReader], which streams the file part to a
 * temporary file and keeps the form fields in memory.
 *
 * The file part may have up to [maxFileSize] bytes, and only one part may be a file. The form
 * fields, together with the headers of all parts, may take up to [FORM_FIELDS_ALLOWANCE], and the
 * request may have up to [MAX_MULTIPART_PARTS] parts. If anything fails, the temporary file is
 * deleted before the exception propagates; otherwise the caller deletes it once the request is done
 * (see [deleteFileParts]).
 *
 * @receiver The call whose `multipart/form-data` body is read.
 * @param maxFileSize Maximum allowed size in bytes for the file part.
 * @return All parts in order: a [FormFieldData] for each form field and a [FilePartData] for the
 *   file.
 * @throws UnsupportedMediaTypeException If the request has no `Content-Type`, or one that is not
 *   `multipart`.
 * @throws BadRequestException If the `Content-Type` header is malformed.
 * @throws MalformedMultipartException If the `Content-Type` names no usable boundary, or the body
 *   is not well-formed multipart or has a part header line longer than 8192 bytes.
 * @throws FormFieldsTooLargeException If the form fields and part headers exceed
 *   [FORM_FIELDS_ALLOWANCE].
 * @throws FileTooLargeException If the file part exceeds [maxFileSize].
 * @throws MultipleFilePartsException If more than one file part is present.
 * @throws TooManyPartsException If the request carries more than [MAX_MULTIPART_PARTS] parts.
 * @throws PayloadTooLargeException If the request body exceeds [requestBodyLimit].
 */
suspend fun RoutingCall.receiveParts(maxFileSize: Long): List<Any> {
	val boundary = multipartBoundary()
	return MultipartFormReader(receiveChannel(), boundary, maxFileSize, MAX_MULTIPART_PARTS, FORM_FIELDS_ALLOWANCE)
		.readParts()
}

/**
 * The `boundary` parameter of the request's `multipart` `Content-Type`.
 *
 * @throws UnsupportedMediaTypeException If the request has no `Content-Type`, or one that is not
 *   `multipart`.
 * @throws BadRequestException If the `Content-Type` header is malformed.
 * @throws MalformedMultipartException If the `Content-Type` names no boundary.
 */
private fun RoutingCall.multipartBoundary(): String {
	val header = request.header(HttpHeaders.ContentType) ?: throw UnsupportedMediaTypeException(null)
	val contentType = try {
		ContentType.parse(header)
	} catch (e: BadContentTypeFormatException) {
		throw BadRequestException(e.message ?: "Malformed Content-Type header", e)
	}
	if (!contentType.match(ContentType.MultiPart.Any)) {
		throw UnsupportedMediaTypeException(contentType)
	}
	return contentType.parameter("boundary")
		?: throw MalformedMultipartException("The multipart Content-Type names no boundary.")
}

/**
 * Delete the temp files backing every [FilePartData] in [this] list, ignoring individual
 * deletion failures.
 *
 * Call this in a `finally` block after [receiveParts] so the streamed upload temp files
 * are removed promptly on every path — success, handled error, or early return. The OS
 * temp-directory reaper is only a backstop for the rare crash-before-finally case; this
 * is the primary, prompt cleanup that keeps the temp directory bounded under load.
 */
fun List<Any>.deleteFileParts() {
	filterIsInstance<FilePartData>().forEach { runCatching { it.file.delete() } }
}

/**
 * Check that [operation] is enabled in [serverConfig] and, if not, respond with
 * `403 Forbidden` and an [ApiError] with code `OPERATION_DISABLED`.
 *
 * @param operation The operation the current route requires.
 * @param serverConfig Current server configuration.
 * @return `true` if the operation is allowed and the handler may proceed;
 *   `false` if the response has already been sent and the handler must return immediately.
 */
suspend fun RoutingCall.requireOperation(
	operation: AllowedOperation,
	serverConfig: ServerConfig,
): Boolean {
	if (operation in serverConfig.operations.allowed) return true
	respond(
		HttpStatusCode.Forbidden,
		ApiError(
			error = "OPERATION_DISABLED",
			message = "The ${operation.name} operation is disabled on this server",
		),
	)
	return false
}

/**
 * Extract a text form field value from collected multipart parts.
 *
 * @param parts Collected multipart parts from [receiveParts].
 * @param name Form field name.
 * @return The field value, or `null` if not present.
 */
fun extractTextField(parts: List<Any>, name: String): String? =
	parts.filterIsInstance<FormFieldData>().firstOrNull { it.name == name }?.value

/**
 * The most unknown values that [parseEnumSetField] names in its error.
 */
private const val MAX_REPORTED_UNKNOWN_VALUES = 10

/**
 * Parse a comma-separated multipart field into a [Set] of [E] enum entries.
 *
 * The field value is split on `,`, trimmed, and matched against [entries] case-insensitively
 * by enum name. An empty or missing field resolves to an empty set. Unknown tokens are
 * rejected eagerly so a typo never silently yields a partial set: a `400 BadRequest` with
 * the supplied [errorCode] is sent and `null` is returned, signalling to the caller that
 * a response has already been written and it must return immediately (mirroring the
 * [requireOperation] convention). The error names at most [MAX_REPORTED_UNKNOWN_VALUES] of
 * them.
 *
 * The value is read one token at a time, and only the set and the named unknown tokens are
 * kept, so a field of many short tokens takes no more memory than its own text.
 *
 * @param parts Collected multipart parts from [receiveParts].
 * @param fieldName Form field name to read.
 * @param entries Allowed enum values, typically `MyEnum.entries`.
 * @param errorCode [ApiError.error] code to use when an unknown token is encountered.
 * @return The parsed set, or `null` when an error response has already been sent.
 */
suspend fun <E : Enum<E>> RoutingCall.parseEnumSetField(
	parts: List<Any>,
	fieldName: String,
	entries: List<E>,
	errorCode: String,
): Set<E>? {
	val raw = extractTextField(parts, fieldName) ?: return emptySet()
	val tokens = raw.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() }
	val byLowerName = entries.associateBy { it.name.lowercase() }
	val unknown = tokens.filter { it.lowercase() !in byLowerName }.take(MAX_REPORTED_UNKNOWN_VALUES).toList()
	if (unknown.isNotEmpty()) {
		respond(
			HttpStatusCode.BadRequest,
			ApiError(
				error = errorCode,
				message = "Unknown value(s) for field '$fieldName': ${unknown.joinToString()}. " +
					"Valid values: ${entries.joinToString { it.name }}.",
			),
		)
		return null
	}
	return tokens.mapNotNullTo(mutableSetOf()) { byLowerName[it.lowercase()] }
}
