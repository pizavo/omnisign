package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.exception.FormFieldsTooLargeException
import cz.pizavo.omnisign.api.exception.MalformedMultipartException
import cz.pizavo.omnisign.api.exception.MultipleFilePartsException
import cz.pizavo.omnisign.api.exception.TooManyPartsException
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.FormFieldData
import io.ktor.http.ContentDisposition
import io.ktor.http.parseHeaderValue
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the parts of a `multipart/form-data` body from [body], holding at most [maxFieldBytes] of
 * form fields and part headers in memory.
 *
 * Ktor's multipart parser reads every header line of a part into memory and has no limit on how
 * many lines a part may have, so a body of a hundred megabytes of short header lines could make the
 * server hold gigabytes. This reader counts each part's header block, as well as the value of each
 * form field, against [maxFieldBytes], and keeps only a part's `Content-Disposition`. Parsing that
 * header takes several times the memory of its text, so the reader also refuses a header line
 * longer than 8192 bytes, much as Ktor refuses one of 8192 bytes or more. It discards the preamble
 * and the spaces after a boundary as it reads them, streams the one file part to a temporary file
 * bounded by [maxFileSize], and stops reading at the closing boundary; the engine discards whatever
 * it has not read of the epilogue.
 *
 * It reads bodies as browsers, curl and Ktor's client write them, following RFC 2046 and RFC 7578:
 * - A part ends at the next CRLF followed by `--` and the boundary. A client picks a boundary that
 *   occurs in none of its parts, so a part's own `Content-Length` header, which RFC 7578 leaves out
 *   of form data, is ignored.
 * - A header line may end with CRLF or with a bare LF.
 * - A body with no boundary line has no parts, but once a part has begun, the body must reach the
 *   closing boundary.
 *
 * Call [readParts] once.
 *
 * @param body The request body.
 * @param boundary The `boundary` parameter of the request's `Content-Type`.
 * @param maxFileSize The most bytes the file part may have.
 * @param maxParts The most parts the body may have, the file included.
 * @param maxFieldBytes The most bytes that the form fields and the header blocks of all parts may
 *   take together.
 * @param createTempFile Creates the file that the file part is streamed to.
 * @throws MalformedMultipartException If [boundary] is not 1 to 70 characters that RFC 2046 allows
 *   in a boundary, or ends with a space.
 */
internal class MultipartFormReader(
	private val body: ByteReadChannel,
	boundary: String,
	private val maxFileSize: Long,
	private val maxParts: Int,
	private val maxFieldBytes: Long,
	private val createTempFile: () -> File = { File.createTempFile("omnisign-upload-", ".pdf") },
) {

	/**
	 * CRLF, `--` and the boundary: what ends a part and starts the next boundary line.
	 */
	private val delimiter: ByteArray

	/**
	 * Bytes read from [body]: those from [position] up to [limit] are not processed yet.
	 *
	 * It starts out holding a CRLF that the body does not have, so that a boundary line at the very
	 * start of the body, which needs no CRLF before it, is found like every later one.
	 */
	private val buffer = ByteArray(BUFFER_SIZE)

	/**
	 * Index in [buffer] of the first byte not processed yet.
	 */
	private var position = 0

	/**
	 * Index in [buffer] just past the last byte read from [body].
	 */
	private var limit = 0

	/**
	 * Set by [findDelimiter] when [buffer] holds no whole delimiter: where a delimiter cut off by the
	 * end of the buffer may begin, or [limit]. The bytes before it belong to the current part.
	 */
	private var safeEnd = 0

	/**
	 * How many more bytes of form fields and part headers [maxFieldBytes] allows.
	 */
	private var fieldBytesLeft = maxFieldBytes

	init {
		if (!isValidBoundary(boundary)) {
			throw MalformedMultipartException(INVALID_BOUNDARY)
		}
		delimiter = "\r\n--$boundary".encodeToByteArray()
		buffer[0] = CR
		buffer[1] = LF
		limit = 2
	}

	/**
	 * Read every part up to the closing boundary, on [Dispatchers.IO], since the file part is written
	 * with blocking I/O.
	 *
	 * If anything fails, every temporary file created so far is deleted before the exception
	 * propagates. That includes the caller being cancelled just after the last part is read: the
	 * switch back from [Dispatchers.IO] then throws [CancellationException] instead of returning the
	 * parts. On success, the caller owns the files and deletes them once the request is done (see
	 * [deleteFileParts]).
	 *
	 * @return The parts in order: a [FormFieldData] for each form field and a [FilePartData] for the
	 *   file.
	 * @throws MalformedMultipartException If a boundary line carries more than the boundary, a header
	 *   line is longer than 8192 bytes, a `Content-Disposition` header is empty, or the body ends
	 *   inside a part.
	 * @throws FormFieldsTooLargeException If the form fields and part headers take more than
	 *   [maxFieldBytes].
	 * @throws TooManyPartsException If the body has more than [maxParts] parts.
	 * @throws MultipleFilePartsException If more than one part names a file.
	 * @throws FileTooLargeException If the file part has more than [maxFileSize] bytes.
	 */
	suspend fun readParts(): List<Any> {
		var parts: List<Any> = emptyList()
		try {
			return withContext(Dispatchers.IO) { readAllParts().also { parts = it } }
		} catch (e: CancellationException) {
			parts.deleteFileParts()
			throw e
		}
	}

	/**
	 * Read every part up to the closing boundary, deleting every temporary file created so far if
	 * anything fails. See [readParts].
	 */
	private suspend fun readAllParts(): List<Any> {
		val parts = mutableListOf<Any>()
		try {
			if (!transferUntilDelimiter(sink = null)) {
				return parts
			}
			while (readBoundaryLineEnd()) {
				if (parts.size == maxParts) {
					throw TooManyPartsException(maxParts)
				}
				parts += readPart(fileSeen = parts.any { it is FilePartData })
			}
		} catch (e: Throwable) {
			parts.deleteFileParts()
			throw e
		}
		return parts
	}

	/**
	 * Read one part, from its header block up to the next delimiter.
	 *
	 * @param fileSeen Whether an earlier part was the file.
	 * @return A [FilePartData] if the part's `Content-Disposition` names a file, else a [FormFieldData].
	 */
	private suspend fun readPart(fileSeen: Boolean): Any {
		val disposition = readHeaderBlock()?.let(::parseContentDisposition)
		val name = disposition?.parameter(ContentDisposition.Parameters.Name)
		val fileName = disposition?.parameter(ContentDisposition.Parameters.FileName)
		return when {
			fileName == null -> readFormField(name)
			fileSeen -> throw MultipleFilePartsException()
			else -> readFilePart(name, fileName)
		}
	}

	/**
	 * Parse a part's `Content-Disposition` header [value] as [ContentDisposition.parse] does, which
	 * throws [NoSuchElementException] for a value that holds nothing to parse, such as an empty one.
	 *
	 * @throws MalformedMultipartException If [value] holds nothing to parse.
	 */
	private fun parseContentDisposition(value: String): ContentDisposition {
		val parsed = parseHeaderValue(value).lastOrNull()
			?: throw MalformedMultipartException(EMPTY_CONTENT_DISPOSITION)
		return ContentDisposition(parsed.value, parsed.params)
	}

	/**
	 * Read a part's header block, up to and including the empty line that ends it, counting every
	 * byte against [maxFieldBytes].
	 *
	 * @return The value of the block's first `Content-Disposition` header, or `null` if it has none.
	 * @throws MalformedMultipartException If a line of the block is longer than
	 *   [MAX_HEADER_LINE_LENGTH] bytes, or the body ends inside it.
	 */
	private suspend fun readHeaderBlock(): String? {
		val block = Buffer()
		var lineLength = 0
		while (true) {
			while (position == limit) {
				if (!fill()) {
					throw MalformedMultipartException(UNEXPECTED_END)
				}
			}
			val start = position
			var end = start
			var complete = false
			while (end < limit && !complete) {
				when (buffer[end++]) {
					LF -> if (lineLength == 0) complete = true else lineLength = 0
					CR -> Unit
					else -> if (++lineLength > MAX_HEADER_LINE_LENGTH) {
						throw MalformedMultipartException(HEADER_LINE_TOO_LONG)
					}
				}
			}
			countFieldBytes(end - start)
			block.write(buffer, start, end)
			position = end
			if (complete) {
				return contentDisposition(block.readByteArray())
			}
		}
	}

	/**
	 * Find the value of the first `Content-Disposition` header among the lines of a header [block].
	 */
	private fun contentDisposition(block: ByteArray): String? {
		var lineStart = 0
		while (lineStart < block.size) {
			var lineEnd = lineStart
			while (lineEnd < block.size && block[lineEnd] != LF) {
				lineEnd++
			}
			if (block.startsWithIgnoringCase(lineStart, lineEnd, CONTENT_DISPOSITION_PREFIX)) {
				return block.decodeToString(lineStart + CONTENT_DISPOSITION_PREFIX.length, lineEnd).trim()
			}
			lineStart = lineEnd + 1
		}
		return null
	}

	/**
	 * Whether the line from [from] to [to] (exclusive) starts with the ASCII [prefix], ignoring case.
	 */
	private fun ByteArray.startsWithIgnoringCase(from: Int, to: Int, prefix: String): Boolean =
		to - from >= prefix.length && prefix.indices.all { offset ->
			Char(this[from + offset].toInt() and 0xFF).lowercaseChar() == prefix[offset]
		}

	/**
	 * Read a form field's value up to the next delimiter, counting it against [maxFieldBytes].
	 *
	 * @param name The field's name, or `null` if its part names none.
	 */
	private suspend fun readFormField(name: String?): FormFieldData {
		val value = Buffer()
		val complete = transferUntilDelimiter { bytes, from, to ->
			countFieldBytes(to - from)
			value.write(bytes, from, to)
		}
		if (!complete) {
			throw MalformedMultipartException(UNEXPECTED_END)
		}
		return FormFieldData(name, value.readString())
	}

	/**
	 * Stream the file part to a new temporary file up to the next delimiter, failing once it passes
	 * [maxFileSize]. If anything fails, the file is deleted before the exception propagates.
	 *
	 * @param name The part's name, or `null` if it names none.
	 * @param fileName The file name the part's `Content-Disposition` gives.
	 */
	private suspend fun readFilePart(name: String?, fileName: String): FilePartData {
		val file = createTempFile()
		try {
			var size = 0L
			val complete = file.outputStream().use { output ->
				transferUntilDelimiter { bytes, from, to ->
					size += to - from
					if (size > maxFileSize) {
						throw FileTooLargeException(actualSize = size, maxSize = maxFileSize)
					}
					output.write(bytes, from, to - from)
				}
			}
			if (!complete) {
				throw MalformedMultipartException(UNEXPECTED_END)
			}
		} catch (e: Throwable) {
			file.delete()
			throw e
		}
		return FilePartData(name, file, fileName)
	}

	/**
	 * Read the rest of a boundary line after its delimiter: `--` on the closing boundary line, and
	 * otherwise spaces or tabs, if any, and a line break.
	 *
	 * @return `true` if a part follows, `false` at the closing boundary.
	 */
	private suspend fun readBoundaryLineEnd(): Boolean {
		var byte = readByte()
		if (byte == '-'.code) {
			if (readByte() == '-'.code) {
				return false
			}
			throw MalformedMultipartException(MALFORMED_BOUNDARY_LINE)
		}
		while (byte == ' '.code || byte == '\t'.code) {
			byte = readByte()
		}
		if (byte == '\r'.code) {
			byte = readByte()
		}
		if (byte != '\n'.code) {
			throw MalformedMultipartException(MALFORMED_BOUNDARY_LINE)
		}
		return true
	}

	/**
	 * Hand the body to [sink] up to the next delimiter, and move past the delimiter.
	 *
	 * @param sink Receives the bytes before the delimiter, as ranges of a shared array from an
	 *   inclusive to an exclusive index, or `null` to discard them.
	 * @return `true` once past the delimiter, `false` if the body ended first.
	 */
	private suspend fun transferUntilDelimiter(sink: ((ByteArray, Int, Int) -> Unit)?): Boolean {
		while (true) {
			val match = findDelimiter()
			if (match >= 0) {
				sink?.invoke(buffer, position, match)
				position = match + delimiter.size
				return true
			}
			sink?.invoke(buffer, position, safeEnd)
			position = safeEnd
			if (!fill()) {
				return false
			}
		}
	}

	/**
	 * Find the next delimiter in the bytes of [buffer] not processed yet.
	 *
	 * A boundary cannot contain a CR, so the delimiter's only CR is its first byte. A match can
	 * therefore start only at a CR, and the bytes that matched before a mismatch cannot start one,
	 * so the search moves past them and reads every byte at most twice, whatever the body holds.
	 * Without a whole delimiter in [buffer], it sets [safeEnd].
	 *
	 * @return The index where the delimiter starts, or -1.
	 */
	private fun findDelimiter(): Int {
		var index = position
		while (index < limit) {
			if (buffer[index] != CR) {
				index++
				continue
			}
			var matched = 1
			while (matched < delimiter.size && index + matched < limit && buffer[index + matched] == delimiter[matched]) {
				matched++
			}
			if (matched == delimiter.size) {
				return index
			}
			if (index + matched == limit) {
				safeEnd = index
				return -1
			}
			index += matched
		}
		safeEnd = limit
		return -1
	}

	/**
	 * Read one byte of the body.
	 *
	 * @return The byte, from 0 to 255, or -1 if the body has ended.
	 */
	private suspend fun readByte(): Int {
		while (position == limit) {
			if (!fill()) {
				return -1
			}
		}
		return buffer[position++].toInt() and 0xFF
	}

	/**
	 * Move the bytes not processed yet to the start of [buffer], and read more of [body] after them.
	 *
	 * `readAvailable` answers a body that failed, for instance by passing the request body limit or
	 * losing its connection, the same way as one that ended, so the failure is rethrown here rather
	 * than read as the end of the body.
	 *
	 * @return `false` if the body has ended.
	 */
	private suspend fun fill(): Boolean {
		buffer.copyInto(buffer, destinationOffset = 0, startIndex = position, endIndex = limit)
		limit -= position
		position = 0
		val read = body.readAvailable(buffer, limit, buffer.size - limit)
		if (read == -1) {
			body.closedCause?.let { throw it }
			return false
		}
		limit += read
		return true
	}

	/**
	 * Count [bytes] more of form fields or part headers against [maxFieldBytes].
	 *
	 * @throws FormFieldsTooLargeException If they now take more than [maxFieldBytes].
	 */
	private fun countFieldBytes(bytes: Int) {
		fieldBytesLeft -= bytes
		if (fieldBytesLeft < 0) {
			throw FormFieldsTooLargeException(maxFieldBytes)
		}
	}

	/**
	 * Whether [boundary] is 1 to 70 characters that RFC 2046 allows in a boundary, not ending with a
	 * space.
	 */
	private fun isValidBoundary(boundary: String): Boolean =
		boundary.length in 1..MAX_BOUNDARY_LENGTH &&
			!boundary.endsWith(' ') &&
			boundary.all { it in BOUNDARY_CHARACTERS }

	companion object {

		/**
		 * How many bytes of the body are read at a time.
		 */
		private const val BUFFER_SIZE = 64 * 1024

		/**
		 * The longest boundary RFC 2046 allows.
		 */
		private const val MAX_BOUNDARY_LENGTH = 70

		/**
		 * The most bytes a header line of a part may have, its line break left out.
		 */
		private const val MAX_HEADER_LINE_LENGTH = 8192

		/**
		 * The characters RFC 2046 allows in a boundary.
		 */
		private const val BOUNDARY_CHARACTERS =
			"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'()+_,-./:=? "

		/**
		 * How a `Content-Disposition` header line starts, in lowercase.
		 */
		private const val CONTENT_DISPOSITION_PREFIX = "content-disposition:"

		/**
		 * A carriage return.
		 */
		private const val CR: Byte = 13

		/**
		 * A line feed.
		 */
		private const val LF: Byte = 10

		/**
		 * The message for a boundary that RFC 2046 does not allow.
		 */
		private const val INVALID_BOUNDARY =
			"The multipart boundary must be 1 to 70 characters that RFC 2046 allows in a boundary."

		/**
		 * The message for a boundary line that carries more than the boundary.
		 */
		private const val MALFORMED_BOUNDARY_LINE = "A multipart boundary line carries more than the boundary."

		/**
		 * The message for a header line longer than [MAX_HEADER_LINE_LENGTH].
		 */
		private const val HEADER_LINE_TOO_LONG = "A header line of a multipart part is longer than 8192 bytes."

		/**
		 * The message for a `Content-Disposition` header that holds nothing to parse.
		 */
		private const val EMPTY_CONTENT_DISPOSITION = "The Content-Disposition header of a multipart part is empty."

		/**
		 * The message for a body that ends inside a part.
		 */
		private const val UNEXPECTED_END = "The multipart body ends inside a part, before its closing boundary."
	}
}
