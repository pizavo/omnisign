package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.api.model.ApiError
import cz.pizavo.omnisign.domain.model.error.OperationError
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.io.File

/**
 * Collect all multipart parts into a list without using the deprecated `readAllParts()`.
 *
 * @receiver The multipart data from the request.
 * @return All parts in order of occurrence.
 */
suspend fun MultiPartData.collectParts(): List<PartData> {
	val result = mutableListOf<PartData>()
	forEachPart { part -> result.add(part) }
	return result
}

/**
 * Extract the first uploaded file part from a multipart data sequence and save it to a temp file.
 *
 * @param parts Multipart parts received from the client.
 * @param name Expected form field name.
 * @return The temporary [File] containing the upload, or `null` if not found.
 */
suspend fun extractFilePart(parts: List<PartData>, name: String): File? {
	val filePart = parts.filterIsInstance<PartData.FileItem>().firstOrNull { it.name == name }
		?: return null

	val tempFile = withContext(Dispatchers.IO) {
		File.createTempFile("omnisign-upload-", ".pdf")
	}
	tempFile.deleteOnExit()

	withContext(Dispatchers.IO) {
		val bytes = filePart.provider().readRemaining().readByteArray()
		tempFile.writeBytes(bytes)
	}

	return tempFile
}

/**
 * Extract a text form field value from multipart parts.
 *
 * @param parts Multipart parts received from the client.
 * @param name Form field name.
 * @return The field value, or `null` if not present.
 */
fun extractTextField(parts: List<PartData>, name: String): String? =
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

