package cz.pizavo.omnisign.data.secret

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.getAttribute
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Verifies the rules [SecretFileReader] applies before it takes a secret from a file, and how it
 * turns the file's content into the secret.
 *
 * Most tests give the reader a fake probe, so the rules about a file's owner, mode and file
 * system run on every operating system while the content still comes from a real temporary file.
 * The tests marked POSIX use the real probe and the real user id.
 */
class SecretFileReaderTest : FunSpec({

	val directory = tempdir().toPath()
	val ownUid = 1000L
	val isPosix = !System.getProperty("os.name").lowercase().contains("win")

	/** Facts of a file the rules accept, for a test to change one of. */
	fun safeFacts() = SecretFileFacts(
		isRegularFile = true,
		size = 6,
		ownerUid = ownUid,
		mode = 0b110_000_000,
		fileSystemType = "tmpfs",
	)

	/** Write [content] to a new file and return it. */
	fun secretFile(content: String): Path = createTempFile(directory, "secret").also { it.writeText(content) }

	/** A reader that sees [facts] for every file and runs as the user [uid]. */
	fun readerSeeing(facts: SecretFileFacts, uid: Long? = ownUid) =
		SecretFileReader(probe = { facts }, effectiveUid = { uid })

	/** Read [path], naming it in a refusal by its path alone. */
	fun SecretFileReader.readFile(path: Path) = read(path, "The file '$path'")

	test("takes the secret without the line break that ends the file") {
		readerSeeing(safeFacts()).readFile(secretFile("s3cret\n")).shouldBeRight().value shouldBe "s3cret"
	}

	test("takes a Windows line break off the end the same way") {
		readerSeeing(safeFacts()).readFile(secretFile("s3cret\r\n")).shouldBeRight().value shouldBe "s3cret"
	}

	test("takes only one line break off the end") {
		readerSeeing(safeFacts()).readFile(secretFile("s3cret\n\n")).shouldBeRight().value shouldBe "s3cret\n"
	}

	test("takes a file without a line break whole") {
		readerSeeing(safeFacts()).readFile(secretFile(" s3cret ")).shouldBeRight().value shouldBe " s3cret "
	}

	test("refuses a file that holds no secret") {
		listOf("", "\n", " \r\n").forAll { content ->
			readerSeeing(safeFacts()).readFile(secretFile(content)).shouldBeLeft().message shouldContain "is empty"
		}
	}

	test("refuses a file that does not exist") {
		val missing = directory.resolve("missing")

		readerSeeing(safeFacts()).readFile(missing).shouldBeLeft().message shouldBe
			"The file '$missing' does not exist."
	}

	test("refuses what is not a regular file") {
		readerSeeing(safeFacts().copy(isRegularFile = false)).readFile(secretFile("s3cret"))
			.shouldBeLeft().message shouldContain "is not a regular file"
	}

	test("refuses a file larger than 64 KiB") {
		val facts = safeFacts().copy(size = SecretFileReader.MAX_SIZE + 1L)

		readerSeeing(facts).readFile(secretFile("s3cret")).shouldBeLeft().message shouldContain "larger than 64 KiB"
	}

	test("refuses a file that grew larger than 64 KiB after it was checked") {
		val file = secretFile("x".repeat(SecretFileReader.MAX_SIZE + 1))

		readerSeeing(safeFacts()).readFile(file).shouldBeLeft().message shouldContain "larger than 64 KiB"
	}

	test("takes a secret of exactly 64 KiB") {
		val file = secretFile("x".repeat(SecretFileReader.MAX_SIZE))
		val facts = safeFacts().copy(size = SecretFileReader.MAX_SIZE.toLong())

		readerSeeing(facts).readFile(file).shouldBeRight().value.length shouldBe SecretFileReader.MAX_SIZE
	}

	test("refuses a file on a file system that holds a container's own files") {
		listOf("overlay", "fuse.fuse-overlayfs", "aufs").forAll { type ->
			readerSeeing(safeFacts().copy(fileSystemType = type)).readFile(secretFile("s3cret"))
				.shouldBeLeft().message shouldContain "Docker or Kubernetes secret"
		}
	}

	test("takes a file from a file system mounted for it, or one that is not named") {
		listOf("tmpfs", "ramfs", "ext4", "apfs", null).forAll { type ->
			readerSeeing(safeFacts().copy(fileSystemType = type)).readFile(secretFile("s3cret")).shouldBeRight()
		}
	}

	test("takes a file that only its owner, the user this process runs as, may access") {
		listOf(0b110_000_000, 0b100_000_000, 0b111_000_000).forAll { mode ->
			readerSeeing(safeFacts().copy(mode = mode)).readFile(secretFile("s3cret")).shouldBeRight()
		}
	}

	test("refuses a file of the user this process runs as that its group or others may access") {
		listOf(0b110_100_000, 0b110_010_000, 0b110_001_000, 0b110_000_100, 0b110_000_001).forAll { mode ->
			readerSeeing(safeFacts().copy(mode = mode)).readFile(secretFile("s3cret"))
				.shouldBeLeft().message shouldContain "chmod 600"
		}
	}

	test("names the refused mode in octal") {
		readerSeeing(safeFacts().copy(mode = 0b110_100_100)).readFile(secretFile("s3cret"))
			.shouldBeLeft().message shouldContain "has mode 0644"
	}

	test("takes a file of root that its group may at most read") {
		listOf(0b110_100_000, 0b100_100_000, 0b110_000_000, 0b100_000_000).forAll { mode ->
			readerSeeing(safeFacts().copy(ownerUid = 0, mode = mode)).readFile(secretFile("s3cret")).shouldBeRight()
		}
	}

	test("refuses a file of root that its group may write or execute, or others may access") {
		listOf(0b110_110_000, 0b110_101_000, 0b110_100_100, 0b100_100_001).forAll { mode ->
			readerSeeing(safeFacts().copy(ownerUid = 0, mode = mode)).readFile(secretFile("s3cret"))
				.shouldBeLeft().message shouldContain "chmod 640"
		}
	}

	test("holds a process that runs as root to the rules for a file of root") {
		val facts = safeFacts().copy(ownerUid = 0, mode = 0b110_100_000)

		readerSeeing(facts, uid = 0).readFile(secretFile("s3cret")).shouldBeRight()
	}

	test("refuses a file owned by another user, however strict its mode") {
		readerSeeing(safeFacts().copy(ownerUid = 1001)).readFile(secretFile("s3cret"))
			.shouldBeLeft().message shouldContain "chown 1000"
	}

	test("refuses a file when it is not known which user this process runs as") {
		readerSeeing(safeFacts(), uid = null).readFile(secretFile("s3cret"))
			.shouldBeLeft().message shouldContain "which user this process runs as"
	}

	test("refuses a file whose owner and mode the platform does not report") {
		val reader = SecretFileReader(probe = { throw UnsupportedOperationException() }, effectiveUid = { ownUid })

		reader.readFile(secretFile("s3cret")).shouldBeLeft().message shouldContain "cannot be checked"
	}

	test("refuses a file that is not UTF-8 text") {
		val file = createTempFile(directory, "secret").also { it.writeBytes(byteArrayOf(0x73, 0xC3.toByte(), 0x28)) }

		readerSeeing(safeFacts()).readFile(file).shouldBeLeft().message shouldContain "is not UTF-8 text"
	}

	test("keeps the secret out of what a refusal and a result print") {
		val refused = readerSeeing(safeFacts().copy(mode = 0b110_100_100)).readFile(secretFile("s3cret"))
		val taken = readerSeeing(safeFacts()).readFile(secretFile("s3cret"))

		refused.toString() shouldNotContain "s3cret"
		taken.toString() shouldNotContain "s3cret"
	}

	test("judges the file a symbolic link leads to").config(enabledIf = { isPosix }) {
		val target = secretFile("s3cret\n")
		val link = directory.resolve("link-to-secret").createSymbolicLinkPointingTo(target)
		var probed: Path? = null
		val reader = SecretFileReader(probe = { probed = it; safeFacts() }, effectiveUid = { ownUid })

		reader.readFile(link).shouldBeRight().value shouldBe "s3cret"
		probed shouldBe target.toRealPath()
	}

	test("knows the user this process runs as").config(enabledIf = { isPosix }) {
		val file = secretFile("s3cret")

		currentEffectiveUid() shouldBe (file.getAttribute("unix:uid") as Int).toUInt().toLong()
	}

	test("takes a secret from a real file only its owner may access").config(enabledIf = { isPosix }) {
		val file = secretFile("s3cret\n").also { it.setPosixFilePermissions(PosixFilePermissions.fromString("rw-------")) }
		val reader = SecretFileReader(probe = { SecretFileFacts.of(it).copy(fileSystemType = null) })

		reader.readFile(file).shouldBeRight().value shouldBe "s3cret"
	}

	test("refuses a real file others may read").config(enabledIf = { isPosix }) {
		val file = secretFile("s3cret\n").also { it.setPosixFilePermissions(PosixFilePermissions.fromString("rw-r--r--")) }
		val reader = SecretFileReader(probe = { SecretFileFacts.of(it).copy(fileSystemType = null) })

		reader.readFile(file).shouldBeLeft().message shouldContain "has mode 0644"
	}

	test("refuses a real directory").config(enabledIf = { isPosix }) {
		val subdirectory = createTempDirectory(directory, "secret")
			.also { it.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------")) }

		SecretFileReader().readFile(subdirectory).shouldBeLeft().message shouldContain "is not a regular file"
	}
})
