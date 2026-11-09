package cz.pizavo.omnisign.data.secret

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * Verifies that [SecretFileFacts.of] reports what the operating system says about a file: its
 * owner and mode where the file system reports them the Unix way, and that it cannot on Windows.
 */
class SecretFileFactsTest : FunSpec({

	val directory = tempdir().toPath()
	val isPosix = !System.getProperty("os.name").lowercase().contains("win")

	test("reports the owner, mode, size and file system of a file").config(enabledIf = { isPosix }) {
		val file = createTempFile(directory, "secret").also {
			it.writeText("s3cret")
			it.setPosixFilePermissions(PosixFilePermissions.fromString("rw-r-----"))
		}

		val facts = SecretFileFacts.of(file)

		facts.isRegularFile shouldBe true
		facts.size shouldBe 6
		facts.mode shouldBe 0b110_100_000
		facts.ownerUid shouldBe currentEffectiveUid()
		facts.fileSystemType.shouldNotBeNull()
	}

	test("reports a directory as no regular file").config(enabledIf = { isPosix }) {
		SecretFileFacts.of(createTempDirectory(directory, "secret")).isRegularFile shouldBe false
	}

	test("reports a symbolic link itself, not the file it leads to").config(enabledIf = { isPosix }) {
		val target = createTempFile(directory, "secret")
		val link = directory.resolve("link-to-secret").createSymbolicLinkPointingTo(target)

		SecretFileFacts.of(link).isRegularFile shouldBe false
	}

	test("cannot report the owner and mode of a file on Windows").config(enabledIf = { !isPosix }) {
		shouldThrow<UnsupportedOperationException> { SecretFileFacts.of(createTempFile(directory, "secret")) }
	}
})
