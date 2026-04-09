package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Collect all multipart parts into a list without using the deprecated `readAllParts()`.
 *
 * File parts are read incrementally chunk-by-chunk and rejected immediately when their
 * accumulated size exceeds [maxFileSize], preventing memory exhaustion from oversized
 * uploads. Non-file parts (form fields) are collected as-is.
 *
 * @receiver The multipart data from the request.
 * @param maxFileSize Maximum allowed size in bytes for each file part.
 *   Defaults to [Long.MAX_VALUE] (unlimited). When exceeded a [FileTooLargeException] is
 *   thrown during the read, well before the full content reaches the heap.
 * @return All parts in order of occurrence. [PartData.FileItem] parts are wrapped as [FilePartData].
 * @throws FileTooLargeException If any single file part exceeds [maxFileSize].
 */
suspend fun MultiPartData.collectParts(maxFileSize: Long = Long.MAX_VALUE): List<Any> {
	val result = mutableListOf<Any>()
	forEachPart { part ->
		if (part is PartData.FileItem) {
			val channel = part.provider()
			val accumulator = java.io.ByteArrayOutputStream()
			val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
			var totalRead = 0L
			while (true) {
				val n = channel.readAvailable(buffer)
				if (n <= 0) break
				totalRead += n
				if (totalRead > maxFileSize) {
					throw FileTooLargeException(actualSize = totalRead, maxSize = maxFileSize)
				}
				accumulator.write(buffer, 0, n)
			}
			result.add(FilePartData(part.name, accumulator.toByteArray()))
		} else {
			result.add(part)
		}
	}
	return result
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
	if (operation in serverConfig.allowedOperations) return true
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
 * Extract the first uploaded file part from collected multipart parts and save it to a temp file.
 *
 * Expects parts collected via [collectParts], where file content is already buffered
 * in [FilePartData] instances. The [maxFileSize] check is a secondary guard; the primary
 * enforcement happens during streaming in [collectParts].
 *
 * @param parts Collected multipart parts from [collectParts].
 * @param name Expected form field name.
 * @param maxFileSize Maximum allowed file size in bytes.
 * @return The temporary [File] containing the upload, or `null` if not found.
 * @throws FileTooLargeException If the file exceeds [maxFileSize].
 */
suspend fun extractFilePart(parts: List<Any>, name: String, maxFileSize: Long = Long.MAX_VALUE): File? {
	val filePart = parts.filterIsInstance<FilePartData>().firstOrNull { it.name == name }
	if (filePart == null) {
		logger.debug { "extractFilePart: no FilePartData with name='$name' in ${parts.size} parts" }
		return null
	}

	logger.debug { "extractFilePart: buffered ${filePart.bytes.size} bytes, maxFileSize=$maxFileSize" }
	if (filePart.bytes.size.toLong() > maxFileSize) {
		throw FileTooLargeException(actualSize = filePart.bytes.size.toLong(), maxSize = maxFileSize)
	}

	val tempFile = withContext(Dispatchers.IO) {
		File.createTempFile("omnisign-upload-", ".pdf")
	}
	tempFile.deleteOnExit()

	withContext(Dispatchers.IO) {
		tempFile.writeBytes(filePart.bytes)
	}

	return tempFile
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



