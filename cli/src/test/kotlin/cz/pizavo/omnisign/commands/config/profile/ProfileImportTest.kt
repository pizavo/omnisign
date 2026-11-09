package cz.pizavo.omnisign.commands.config.profile

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ConfigArchiveUseCase
import cz.pizavo.omnisign.domain.usecase.ExportImportConfigUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.koin.dsl.module
import java.io.File

/**
 * Behavioral tests for the [ProfileImport] command which, like `config import`, first works out what
 * it was handed: a ZIP archive, recognized by its magic bytes, or a legacy text profile whose format
 * comes from `--format` or the file's extension.
 *
 * Every failure path, from an unreadable file to a format that cannot be inferred to an import that
 * fails, exits with status 1, so a provisioning script never goes on to sign with a profile that was
 * not imported.
 */
class ProfileImportTest : FunSpec({

	val archive: ConfigArchiveUseCase = mockk()
	val exportImport: ExportImportConfigUseCase = mockk()

	extension(
		KoinExtension(
			module {
				single { archive }
				single { exportImport }
			},
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(archive, exportImport) }

	val tmpDir = tempdir()

	/** Write [content] to a fresh file named [fileName] and return its absolute path. */
	fun fileWith(fileName: String, content: ByteArray): String =
		File(tmpDir, fileName).also { it.writeBytes(content) }.absolutePath

	val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)

	test("routes a ZIP archive to the archive importer, under the name given") {
		coEvery { archive.importProfile(any(), any()) } returns "renamed".right()

		val result = Omnisign().test(
			listOf("config", "profile", "import", fileWith("profile.zip", zipBytes), "--name", "renamed"),
		)

		result.statusCode shouldBe 0
		result.stdout shouldContain "Profile 'renamed' imported"
		coVerify { archive.importProfile(any(), "renamed") }
		coVerify(exactly = 0) { exportImport.importProfile(any(), any(), any()) }
	}

	test("routes a legacy text file to the text importer with the inferred format") {
		coEvery { exportImport.importProfile(any(), any(), any()) } returns "qualified".right()

		val result = Omnisign().test(
			listOf("config", "profile", "import", fileWith("legacy.yaml", "name: qualified".encodeToByteArray())),
		)

		result.statusCode shouldBe 0
		coVerify { exportImport.importProfile("name: qualified", ConfigFormat.YAML, null) }
		coVerify(exactly = 0) { archive.importProfile(any(), any()) }
	}

	test("refuses a text file whose format cannot be inferred") {
		val result = Omnisign().test(
			listOf("config", "profile", "import", fileWith("profile.backup", "{}".encodeToByteArray())),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Cannot infer format"
		coVerify(exactly = 0) { exportImport.importProfile(any(), any(), any()) }
	}

	test("reports an unreadable file and exits non-zero") {
		val result = Omnisign().test(
			listOf("config", "profile", "import", File(tmpDir, "absent.zip").absolutePath),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Cannot read file"
		coVerify(exactly = 0) { archive.importProfile(any(), any()) }
	}

	test("reports an import failure and exits non-zero") {
		coEvery { archive.importProfile(any(), any()) } returns
			ConfigurationError.loadFailed(details = "manifest entry missing").left()

		val result = Omnisign().test(listOf("config", "profile", "import", fileWith("broken.zip", zipBytes)))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Import failed"
		result.stderr shouldContain "manifest entry missing"
	}
})
