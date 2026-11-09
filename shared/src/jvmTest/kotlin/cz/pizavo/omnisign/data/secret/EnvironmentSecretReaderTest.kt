package cz.pizavo.omnisign.data.secret

import cz.pizavo.omnisign.domain.model.value.sensitive
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

/**
 * Verifies where [EnvironmentSecretReader] looks for a secret — the variable, the file
 * `NAME_FILE` names, and the systemd credential — and that it refuses a secret set in more than
 * one of them, or in a file on Windows.
 *
 * The file reader it is given accepts every file, so these tests run on every operating system;
 * the rules for a file are [SecretFileReaderTest]'s.
 */
class EnvironmentSecretReaderTest : FunSpec({

	val directory = tempdir().toPath()
	val name = "OMNISIGN_TEST_SECRET"
	val fileVariable = "OMNISIGN_TEST_SECRET_FILE"
	val acceptingFileReader = SecretFileReader(
		probe = { SecretFileFacts(isRegularFile = true, size = it.fileSize(), ownerUid = 1000, mode = 0b110_000_000, fileSystemType = "tmpfs") },
		effectiveUid = { 1000 },
	)
	val reader = EnvironmentSecretReader(acceptingFileReader, osName = "Linux")
	val windowsReader = EnvironmentSecretReader(acceptingFileReader, osName = "Windows 11")

	/** An environment that holds only [variables]. */
	fun environmentOf(vararg variables: Pair<String, String>): (String) -> String? = mapOf(*variables)::get

	/** Write [content] to a new file and return its path. */
	fun secretFile(content: String): Path = createTempFile(directory, "secret").also { it.writeText(content) }

	/** Make a credentials directory, as systemd would, that holds [content] as the credential [name]. */
	fun credentialsDirectoryWith(content: String): Path =
		createTempDirectory(directory, "credentials").also { it.resolve(name).writeText(content) }

	test("finds no secret when none is set") {
		reader.read(name, environmentOf()).shouldBeRight().shouldBeNull()
	}

	test("takes the secret from the variable") {
		reader.read(name, environmentOf(name to "s3cret")).shouldBeRight()?.value shouldBe "s3cret"
	}

	test("treats a blank variable as unset") {
		reader.read(name, environmentOf(name to " ", fileVariable to "")).shouldBeRight().shouldBeNull()
	}

	test("takes the secret from the file NAME_FILE names") {
		val file = secretFile("s3cret\n")

		reader.read(name, environmentOf(fileVariable to file.toString())).shouldBeRight()?.value shouldBe "s3cret"
	}

	test("takes the secret from the systemd credential") {
		val credentials = credentialsDirectoryWith("s3cret\n")
		val environment = environmentOf(EnvironmentSecretReader.CREDENTIALS_DIRECTORY to credentials.toString())

		reader.read(name, environment).shouldBeRight()?.value shouldBe "s3cret"
	}

	test("finds no secret in a credentials directory without this credential") {
		val credentials = createTempDirectory(directory, "credentials")
		val environment = environmentOf(EnvironmentSecretReader.CREDENTIALS_DIRECTORY to credentials.toString())

		reader.read(name, environment).shouldBeRight().shouldBeNull()
	}

	test("refuses a secret set both in the variable and in a file") {
		val environment = environmentOf(name to "s3cret", fileVariable to secretFile("other").toString())

		reader.read(name, environment).shouldBeLeft().message shouldBe
			"Both $name and $fileVariable are set. Set only one of them."
	}

	test("refuses a secret set both in a file and as a systemd credential") {
		val environment = environmentOf(
			fileVariable to secretFile("s3cret").toString(),
			EnvironmentSecretReader.CREDENTIALS_DIRECTORY to credentialsDirectoryWith("other").toString(),
		)

		reader.read(name, environment).shouldBeLeft().message shouldBe
			"Both $fileVariable and the systemd credential $name are set. Set only one of them."
	}

	test("refuses a secret set in all three places") {
		val environment = environmentOf(
			name to "s3cret",
			fileVariable to secretFile("other").toString(),
			EnvironmentSecretReader.CREDENTIALS_DIRECTORY to credentialsDirectoryWith("another").toString(),
		)

		reader.read(name, environment).shouldBeLeft().message shouldBe
			"$name, $fileVariable and the systemd credential $name are all set. Set only one of them."
	}

	test("locates a secret file without reading it") {
		val missing = directory.resolve("missing").toString()

		reader.locate(name, environmentOf(fileVariable to missing)).shouldBeRight() shouldBe
			SecretSource.File(name, missing)
	}

	test("passes on why a secret file was refused, naming the variable") {
		val missing = directory.resolve("missing").toString()

		reader.read(name, environmentOf(fileVariable to missing)).shouldBeLeft().message shouldBe
			"The file '$missing' named by $fileVariable does not exist."
	}

	test("refuses a secret file on Windows") {
		val environment = environmentOf(fileVariable to secretFile("s3cret").toString())

		windowsReader.read(name, environment).shouldBeLeft().message shouldBe
			"$fileVariable cannot be used on Windows, where it cannot be checked who may read a file. " +
			"Set $name instead."
	}

	test("takes the variable on Windows") {
		windowsReader.read(name, environmentOf(name to "s3cret")).shouldBeRight()?.value shouldBe "s3cret"
	}

	test("does not look for systemd credentials on Windows") {
		val environment = environmentOf(EnvironmentSecretReader.CREDENTIALS_DIRECTORY to credentialsDirectoryWith("s3cret").toString())

		windowsReader.read(name, environment).shouldBeRight().shouldBeNull()
	}

	test("does not look for a credential whose name would leave the directory") {
		val nested = credentialsDirectoryWith("s3cret").resolve("nested").createDirectory()
		val environment = environmentOf(EnvironmentSecretReader.CREDENTIALS_DIRECTORY to nested.toString())

		reader.locate("../$name", environment).shouldBeRight().shouldBeNull()
	}

	test("keeps the secret out of what a found variable prints") {
		val source = reader.locate(name, environmentOf(name to "s3cret")).shouldBeRight()

		source.shouldNotBeNull().shouldBeInstanceOf<SecretSource.Variable>()
		source.toString() shouldNotContain "s3cret"
		source shouldBe SecretSource.Variable(name, "s3cret".sensitive())
	}
})
