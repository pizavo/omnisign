package cz.pizavo.omnisign.commands.config

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
 * Behavioral tests for the [ConfigImport] command, whose job before delegating is to work out what
 * it was handed: a ZIP archive, recognized by its magic bytes rather than its name, or a legacy
 * plain-text configuration whose format has to be inferred from the extension.
 *
 * Misreading that distinction is the failure worth guarding against — a ZIP fed to the text importer
 * would be decoded as mojibake, and a text file routed to the archive importer would be rejected for
 * the wrong reason. Every failure path is checked for a non-zero exit so a provisioning script never
 * proceeds on an import that silently did nothing.
 */
class ConfigImportTest : FunSpec({

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

	test("routes a ZIP archive to the archive importer") {
		coEvery { archive.importGlobal(any()) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "import", fileWith("backup.zip", zipBytes)))

		result.statusCode shouldBe 0
		coVerify { archive.importGlobal(any()) }
		coVerify(exactly = 0) { exportImport.importGlobal(any(), any()) }
	}

	test("recognizes an archive by its magic bytes rather than its extension") {
		coEvery { archive.importGlobal(any()) } returns Unit.right()

		Omnisign().test(listOf("config", "import", fileWith("mislabelled.yaml", zipBytes)))

		coVerify { archive.importGlobal(any()) }
		coVerify(exactly = 0) { exportImport.importGlobal(any(), any()) }
	}

	test("routes a legacy text file to the text importer with the inferred format") {
		coEvery { exportImport.importGlobal(any(), any()) } returns Unit.right()

		val result = Omnisign().test(
			listOf("config", "import", fileWith("legacy.yaml", "global: {}".encodeToByteArray())),
		)

		result.statusCode shouldBe 0
		coVerify { exportImport.importGlobal("global: {}", ConfigFormat.YAML) }
	}

	test("lets an explicit format override the extension for a text file") {
		coEvery { exportImport.importGlobal(any(), any()) } returns Unit.right()

		Omnisign().test(
			listOf(
				"config", "import",
				fileWith("legacy.txt", "{}".encodeToByteArray()),
				"--format", "JSON",
			),
		)

		coVerify { exportImport.importGlobal("{}", ConfigFormat.JSON) }
	}

	test("imports the whole application configuration on --all") {
		coEvery { archive.importApp(any()) } returns Unit.right()

		val result = Omnisign().test(
			listOf("config", "import", fileWith("full.zip", zipBytes), "--all"),
		)

		coVerify { archive.importApp(any()) }
		coVerify(exactly = 0) { archive.importGlobal(any()) }
		result.stdout shouldContain "full application"
	}

	test("refuses a text file whose format cannot be inferred") {
		val result = Omnisign().test(
			listOf("config", "import", fileWith("config.backup", "{}".encodeToByteArray())),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Cannot infer format"
		coVerify(exactly = 0) { exportImport.importGlobal(any(), any()) }
	}

	test("reports an unreadable file and exits non-zero") {
		val result = Omnisign().test(
			listOf("config", "import", File(tmpDir, "absent.zip").absolutePath),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Cannot read file"
		coVerify(exactly = 0) { archive.importGlobal(any()) }
	}

	test("reports an import failure and exits non-zero") {
		coEvery { archive.importGlobal(any()) } returns
			ConfigurationError.loadFailed(details = "manifest entry missing").left()

		val result = Omnisign().test(listOf("config", "import", fileWith("broken.zip", zipBytes)))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Import failed"
		result.stderr shouldContain "manifest entry missing"
	}
})
