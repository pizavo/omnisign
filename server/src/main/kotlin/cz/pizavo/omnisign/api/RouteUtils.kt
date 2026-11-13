package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.exception.MultipleFilePartsException
import cz.pizavo.omnisign.api.exception.TooManyPartsException
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Room a request body has beyond the file-size limit, for the form fields, part headers and
 * boundaries that come with the file. See [requestBodyLimit].
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
 * [configureRequestBodyLimit][cz.pizavo.omnisign.plugins.configureRequestBodyLimit]), which also
 * bounds the form fields that Ktor's multipart parser reads into memory.
 */
fun requestBodyLimit(maxFileSize: Long): Long =
	maxFileSize.coerceAtMost(Long.MAX_VALUE - FORM_FIELDS_ALLOWANCE) + FORM_FIELDS_ALLOWANCE

/**
 * Receive the request's multipart body and [collect its parts][collectParts], bounding the file
 * part by [maxFileSize].
 *
 * Ktor's multipart parser caps every part, files included, at its `formFieldLimit`, which
 * defaults to 50 MiB; with a [maxFileSize] above that, uploads between the two would be answered
 * with HTTP 500. The cap is raised to the [request body limit][requestBodyLimit] instead. No part
 * can reach it, because the body that holds the part is held to the same limit and stops first,
 * with [PayloadTooLargeException].
 *
 * @receiver The call whose `multipart/form-data` body is received.
 * @param maxFileSize Maximum allowed size in bytes for the file part.
 * @return All parts in order of occurrence; see [collectParts].
 * @throws FileTooLargeException If the file part exceeds [maxFileSize].
 * @throws MultipleFilePartsException If more than one file part is present.
 * @throws TooManyPartsException If the request carries more than [MAX_MULTIPART_PARTS] parts.
 * @throws PayloadTooLargeException If the request body exceeds [requestBodyLimit].
 */
suspend fun RoutingCall.receiveParts(maxFileSize: Long): List<Any> =
	receiveMultipart(formFieldLimit = requestBodyLimit(maxFileSize)).collectParts(maxFileSize)

/**
 * Collect all multipart parts into a list without using the deprecated `readAllParts()`.
 *
 * File parts are streamed chunk-by-chunk **directly to a temporary file** as they arrive,
 * never accumulating the full content in heap: a file part takes one [DEFAULT_BUFFER_SIZE]
 * buffer regardless of its size. Each file part's content lands in
 * its own temp file in the OS temp directory; the resulting [FilePartData] holds the
 * [File] path handle. The caller owns the temp-file lifecycle and must delete them once
 * the request completes (see [deleteFileParts]).
 *
 * Size is enforced during the stream: when a part's accumulated size exceeds [maxFileSize]
 * a [FileTooLargeException] is thrown mid-stream, before the whole file reaches disk.
 *
 * **Single-file contract**: at most one file part is accepted. A second [PartData.FileItem]
 * raises [MultipleFilePartsException] — the signing / validation / timestamping endpoints
 * each operate on exactly one document, and rejecting extra file parts bounds the
 * per-request disk footprint to one file.
 *
 * **Bounded form fields**: Ktor's parser reads each non-file part into memory before handing it
 * over, and they are kept until the request completes. The [request body limit][requestBodyLimit]
 * bounds their total size, and [maxParts] bounds their number, which a body of tiny parts would
 * otherwise turn into millions of objects: the part after the [maxParts]th raises
 * [TooManyPartsException].
 *
 * **Self-cleaning on failure**: if any exception is raised mid-collection (size violation,
 * second file part, too many parts, I/O error), every temp file created so far is deleted before
 * the exception propagates, so a failed collection leaves nothing behind. On success the
 * caller takes ownership.
 *
 * @receiver The multipart data from the request.
 * @param maxFileSize Maximum allowed size in bytes for the file part. Defaults to
 *   [Long.MAX_VALUE] (unlimited).
 * @param maxParts Maximum number of parts, the file included. Defaults to [MAX_MULTIPART_PARTS].
 * @return All parts in order of occurrence. [PartData.FileItem] parts are wrapped as
 *   [FilePartData] backed by a temp file.
 * @throws FileTooLargeException If the file part exceeds [maxFileSize].
 * @throws MultipleFilePartsException If more than one file part is present.
 * @throws TooManyPartsException If more than [maxParts] parts are present.
 */
suspend fun MultiPartData.collectParts(
	maxFileSize: Long = Long.MAX_VALUE,
	maxParts: Int = MAX_MULTIPART_PARTS,
): List<Any> {
	val result = mutableListOf<Any>()
	val createdFiles = mutableListOf<File>()
	try {
		forEachPart { part ->
			if (result.size == maxParts) {
				throw TooManyPartsException(maxParts)
			}
			if (part is PartData.FileItem) {
				if (createdFiles.isNotEmpty()) {
					throw MultipleFilePartsException()
				}
				val tempFile = withContext(Dispatchers.IO) {
					File.createTempFile("omnisign-upload-", ".pdf")
				}
				createdFiles.add(tempFile)
				streamPartToFile(part, tempFile, maxFileSize)
				result.add(FilePartData(part.name, tempFile, part.originalFileName))
			} else {
				result.add(part)
			}
		}
	} catch (e: Throwable) {
		createdFiles.forEach { runCatching { it.delete() } }
		throw e
	}
	return result
}

/**
 * Stream a single [PartData.FileItem]'s channel into [target], enforcing [maxFileSize]
 * as the running total grows. Uses a fixed [DEFAULT_BUFFER_SIZE] buffer so heap usage is
 * constant regardless of file size.
 *
 * @throws FileTooLargeException when the accumulated byte count exceeds [maxFileSize].
 */
private suspend fun streamPartToFile(part: PartData.FileItem, target: File, maxFileSize: Long) {
	val channel = part.provider()
	withContext(Dispatchers.IO) {
		target.outputStream().use { out ->
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			var totalRead = 0L
			while (true) {
				val n = channel.readAvailable(buffer)
				if (n <= 0) break
				totalRead += n
				if (totalRead > maxFileSize) {
					throw FileTooLargeException(actualSize = totalRead, maxSize = maxFileSize)
				}
				out.write(buffer, 0, n)
			}
		}
	}
}

/**
 * Delete the temp files backing every [FilePartData] in [this] list, ignoring individual
 * deletion failures.
 *
 * Call this in a `finally` block after [collectParts] so the streamed upload temp files
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
 * @param parts Collected multipart parts from [collectParts].
 * @param name Form field name.
 * @return The field value, or `null` if not present.
 */
fun extractTextField(parts: List<Any>, name: String): String? =
	parts.filterIsInstance<PartData.FormItem>().firstOrNull { it.name == name }?.value

/**
 * Parse a comma-separated multipart field into a [Set] of [E] enum entries.
 *
 * The field value is split on `,`, trimmed, and matched against [entries] case-insensitively
 * by enum name. An empty or missing field resolves to an empty set. Unknown tokens are
 * rejected eagerly so a typo never silently yields a partial set: a `400 BadRequest` with
 * the supplied [errorCode] is sent and `null` is returned, signalling to the caller that
 * a response has already been written and it must return immediately (mirroring the
 * [requireOperation] convention).
 *
 * @param parts Collected multipart parts from [collectParts].
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
	val tokens = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
	val byLowerName = entries.associateBy { it.name.lowercase() }
	val unknown = tokens.filter { it.lowercase() !in byLowerName }
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



