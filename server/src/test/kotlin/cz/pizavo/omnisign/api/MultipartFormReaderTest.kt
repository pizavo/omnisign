package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.api.exception.FileTooLargeException
import cz.pizavo.omnisign.api.exception.FormFieldsTooLargeException
import cz.pizavo.omnisign.api.exception.MalformedMultipartException
import cz.pizavo.omnisign.api.exception.MultipleFilePartsException
import cz.pizavo.omnisign.api.exception.TooManyPartsException
import cz.pizavo.omnisign.api.model.FilePartData
import cz.pizavo.omnisign.api.model.FormFieldData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.io.Buffer
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Verifies [MultipartFormReader]: what it reads from well-formed bodies, including ones written by
 * Ktor's client and ones that arrive a few bytes at a time, and that it holds a body to its limits,
 * refuses malformed ones and deletes the temporary file of a part it gives up on.
 *
 * The limit on part headers is the reason the reader exists: Ktor's own parser keeps every header
 * line of a part, however many there are.
 */
class MultipartFormReaderTest : FunSpec({

	val directory = tempdir()

	val createdFiles = mutableListOf<File>()

	beforeTest { createdFiles.clear() }

	fun reader(
		body: ByteReadChannel,
		maxFileSize: Long = MAX_FILE_SIZE,
		maxParts: Int = MAX_PARTS,
		maxFieldBytes: Long = MAX_FIELD_BYTES,
		boundary: String = BOUNDARY,
	) = MultipartFormReader(body, boundary, maxFileSize, maxParts, maxFieldBytes) {
		File.createTempFile("reader-test-", ".pdf", directory).also { createdFiles += it }
	}

	suspend fun read(
		body: ByteArray,
		maxFileSize: Long = MAX_FILE_SIZE,
		maxParts: Int = MAX_PARTS,
		maxFieldBytes: Long = MAX_FIELD_BYTES,
	): List<Any> = reader(ByteReadChannel(body), maxFileSize, maxParts, maxFieldBytes).readParts()

	test("reads form fields and streams the file part to a temporary file") {
		val document = Random(1).nextBytes(10_000)
		val parts = read(field("profile", "strict") + filePart("document.pdf", document) + closing())

		parts shouldHaveSize 2
		parts[0].shouldBeInstanceOf<FormFieldData>().run {
			name shouldBe "profile"
			value shouldBe "strict"
		}
		parts[1].shouldBeInstanceOf<FilePartData>().run {
			name shouldBe "file"
			originalFileName shouldBe "document.pdf"
			file.readBytes() shouldBe document
		}
	}

	test("reads a body that Ktor's client writes") {
		val document = Random(2).nextBytes(10_000)
		val content = MultiPartFormDataContent(formData {
			append("reason", "Schváleno – approved")
			append("file", document, Headers.build {
				append(HttpHeaders.ContentDisposition, "filename=\"document.pdf\"")
				append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
			})
		})
		val body = ByteChannel()
		content.writeTo(body)
		body.flushAndClose()

		val parts = reader(body, boundary = content.boundary).readParts()

		parts shouldHaveSize 2
		parts[0].shouldBeInstanceOf<FormFieldData>().value shouldBe "Schváleno – approved"
		parts[1].shouldBeInstanceOf<FilePartData>().run {
			name shouldBe "file"
			originalFileName shouldBe "document.pdf"
			file.readBytes() shouldBe document
		}
	}

	test("finds every delimiter, and only those, when the body arrives a few bytes at a time") {
		val lookalikes = "\r\n--$BOUNDARY".dropLast(1) + "X" + "\r\r\n--" + BOUNDARY.take(5) + "\r\n-\r" +
			"--$BOUNDARY\r\n" + "\r\n--${BOUNDARY.uppercase()}\r\n"
		val document = (lookalikes.repeat(20) + "\r").encodeToByteArray()
		val body = field("reason", lookalikes) + filePart("document.pdf", document) + closing()

		for (step in 1..9) {
			val parts = reader(TrickleByteReadChannel(body, step)).readParts()

			parts shouldHaveSize 2
			parts[0].shouldBeInstanceOf<FormFieldData>().value shouldBe lookalikes
			parts[1].shouldBeInstanceOf<FilePartData>().file.readBytes() shouldBe document
		}
	}

	test("accepts header lines that end with a bare LF, and spaces or tabs after a boundary") {
		val body = ascii(
			"--$BOUNDARY \t\r\nContent-Disposition: form-data; name=\"first\"\n\none\r\n" +
				"--$BOUNDARY\nContent-Disposition: form-data; name=\"second\"\r\n\r\ntwo\r\n--$BOUNDARY--",
		)

		read(body).map { (it as FormFieldData).name to it.value } shouldBe listOf("first" to "one", "second" to "two")
	}

	test("skips the preamble and ignores the epilogue") {
		val body = ascii("A preamble, with --$BOUNDARY inside a line.\r\n") + field("profile", "strict") +
			closing() + ascii("An epilogue.\r\n--$BOUNDARY\r\n")

		read(body).map { (it as FormFieldData).value } shouldBe listOf("strict")
	}

	test("ignores a part's own Content-Length and keeps its first Content-Disposition, in any case") {
		val body = ascii(
			"--$BOUNDARY\r\nCONTENT-DISPOSITION: form-data; name=\"first\"\r\nContent-Length: 2\r\n" +
				"content-disposition: form-data; name=\"second\"\r\n\r\nlonger than two bytes\r\n--$BOUNDARY--",
		)

		read(body).single().shouldBeInstanceOf<FormFieldData>().run {
			name shouldBe "first"
			value shouldBe "longer than two bytes"
		}
	}

	test("reads a body without a boundary line as one without parts") {
		read(ascii("No boundary here.")).shouldBeEmpty()
		read(ByteArray(0)).shouldBeEmpty()
	}

	test("counts the header lines of a part against the form-field allowance") {
		val manyHeaders = "X-Filler: y\r\n".repeat(200)
		val body = ascii(
			"--$BOUNDARY\r\nContent-Disposition: form-data; name=\"profile\"\r\n$manyHeaders\r\nstrict\r\n--$BOUNDARY--",
		)

		shouldThrow<FormFieldsTooLargeException> { read(body, maxFieldBytes = 2048) }.maxBytes shouldBe 2048
		read(body, maxFieldBytes = 4096).single().shouldBeInstanceOf<FormFieldData>().value shouldBe "strict"
	}

	test("counts form-field values against the allowance, but not the file") {
		val twoFields = field("reason", "r".repeat(700)) + field("location", "l".repeat(700)) + closing()

		shouldThrow<FormFieldsTooLargeException> { read(twoFields, maxFieldBytes = 1024) }
		read(filePart("document.pdf", ByteArray(8 * 1024)) + closing(), maxFieldBytes = 1024) shouldHaveSize 1
	}

	test("refuses the part after maxParts") {
		val threeFields = field("a", "1") + field("b", "2") + field("c", "3") + closing()

		shouldThrow<TooManyPartsException> { read(threeFields, maxParts = 2) }.maxParts shouldBe 2
		read(threeFields, maxParts = 3) shouldHaveSize 3
	}

	test("refuses a second file part and deletes the first part's file") {
		val body = filePart("a.pdf", ByteArray(64)) + filePart("b.pdf", ByteArray(64)) + closing()

		shouldThrow<MultipleFilePartsException> { read(body) }
		createdFiles shouldHaveSize 1
		createdFiles.single().exists() shouldBe false
	}

	test("refuses a file part over maxFileSize and deletes its file") {
		shouldThrow<FileTooLargeException> { read(filePart("a.pdf", ByteArray(101)) + closing(), maxFileSize = 100) }
		createdFiles.single().exists() shouldBe false

		read(filePart("a.pdf", ByteArray(100)) + closing(), maxFileSize = 100) shouldHaveSize 1
	}

	test("refuses a body that ends inside a part, and deletes the part's file") {
		shouldThrow<MalformedMultipartException> { read(filePart("a.pdf", ByteArray(64))) }
		createdFiles.single().exists() shouldBe false

		shouldThrow<MalformedMultipartException> { read(field("a", "value")) }
		shouldThrow<MalformedMultipartException> {
			read(ascii("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"a\"\r\n"))
		}
		shouldThrow<MalformedMultipartException> { read(field("a", "value") + ascii("--$BOUNDARY")) }
	}

	test("refuses a boundary line that carries more than the boundary") {
		shouldThrow<MalformedMultipartException> { read(field("a", "value") + ascii("--${BOUNDARY}x\r\n")) }
		shouldThrow<MalformedMultipartException> { read(field("a", "value") + ascii("--$BOUNDARY-\r\n")) }
	}

	test("passes on a failure of the body instead of reading it as the end of the body") {
		val failure = IOException("Connection reset")
		val body = TrickleByteReadChannel(field("a", "value"), step = 4, failure = failure)

		shouldThrow<IOException> { reader(body).readParts() } shouldBeSameInstanceAs failure
	}

	test("refuses a boundary that RFC 2046 does not allow") {
		for (boundary in listOf("", "b".repeat(71), "carriage\rreturn", "ends with a space ", "semi;colon")) {
			shouldThrow<MalformedMultipartException> { reader(ByteReadChannel(ByteArray(0)), boundary = boundary) }
		}
		reader(ByteReadChannel(ByteArray(0)), boundary = "b".repeat(70)).readParts().shouldBeEmpty()
		reader(ByteReadChannel(ByteArray(0)), boundary = "'()+_,-./:=? x").readParts().shouldBeEmpty()
	}
}) {
	companion object {

		/** Boundary of the bodies the tests build by hand. */
		private const val BOUNDARY = "reader-test-boundary"

		/** Default `maxFileSize` of a reader under test. */
		private const val MAX_FILE_SIZE = 1024L * 1024

		/** Default `maxParts` of a reader under test. */
		private const val MAX_PARTS = 32

		/** Default `maxFieldBytes` of a reader under test. */
		private const val MAX_FIELD_BYTES = 64L * 1024

		/** [text], which has only ASCII characters, as bytes. */
		fun ascii(text: String): ByteArray = text.encodeToByteArray()

		/** A boundary line and a form field named [name] with [value], ending before the next delimiter. */
		fun field(name: String, value: String): ByteArray =
			ascii("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n") +
				value.encodeToByteArray() + ascii("\r\n")

		/** A boundary line and a file part named `file` with [content], ending before the next delimiter. */
		fun filePart(fileName: String, content: ByteArray): ByteArray =
			ascii(
				"--$BOUNDARY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
					"Content-Type: application/pdf\r\n\r\n",
			) + content + ascii("\r\n")

		/** The closing boundary line. */
		fun closing(): ByteArray = ascii("--$BOUNDARY--\r\n")

		/**
		 * A [ByteReadChannel] that hands out [bytes] at most [step] bytes at a time, so that a
		 * reader's reads end at every offset of the delimiters in them, and then ends, or fails with
		 * [failure] if one is given.
		 */
		@OptIn(InternalAPI::class)
		private class TrickleByteReadChannel(
			private val bytes: ByteArray,
			private val step: Int,
			private val failure: Throwable? = null,
		) : ByteReadChannel {

			private val pending = Buffer()

			private var offset = 0

			override val closedCause: Throwable?
				get() = if (offset == bytes.size) failure else null

			override val isClosedForRead: Boolean
				get() = pending.exhausted() && offset == bytes.size

			@InternalAPI
			override val readBuffer: Buffer
				get() = pending

			override suspend fun awaitContent(min: Int): Boolean {
				if (pending.exhausted() && offset < bytes.size) {
					val end = minOf(offset + step, bytes.size)
					pending.write(bytes, offset, end)
					offset = end
				}
				return !pending.exhausted()
			}

			override fun cancel(cause: Throwable?) {
				pending.clear()
				offset = bytes.size
			}
		}
	}
}
