package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.domain.model.error.OperationError
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
 * File part content is eagerly read into [FilePartData.bytes] during iteration because
 * Ktor 3.x does not guarantee that [PartData.FileItem.provider] remains readable after
 * the multipart stream has been fully consumed.
 *
 * @receiver The multipart data from the request.
 * @return All parts in order of occurrence. [PartData.FileItem] parts are wrapped as [FilePartData].
 */
suspend fun MultiPartData.collectParts(): List<Any> {
	val result = mutableListOf<Any>()
	forEachPart { part ->
		if (part is PartData.FileItem) {
			result.add(FilePartData(part.name, part.provider().toByteArray()))
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
 * in [FilePartData] instances.
 *
 * @param parts Collected multipart parts from [collectParts].
 * @param name Expected form field name.
 * @param maxFileSize Maximum allowed file size in bytes. When the uploaded content exceeds
 *   this limit a [FileTooLargeException] is thrown.
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

/**
 * Respond with a structured [ApiError] for a domain [OperationError].
 */
suspend fun RoutingCall.respondError(status: HttpStatusCode, error: OperationError) {
	respond(
		status,
		ApiError(
			error = error::class.simpleName ?: "UNKNOWN",
			message = error.message,
			details = error.details,
		),
	)
}

