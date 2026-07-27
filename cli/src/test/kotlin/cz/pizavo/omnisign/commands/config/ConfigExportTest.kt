package cz.pizavo.omnisign.commands.config

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ConfigArchiveUseCase
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
import kotlin.io.path.readBytes

/**
 * Behavioral tests for the [ConfigExport] command, covering the two decisions it makes before
 * delegating: which configuration format ends up inside the archive, and whether the export covers
 * the global section or the whole application.
 *
 * Format resolution is a three-step fallback — explicit `--format`, then the output file's
 * extension, then JSON — and getting it wrong writes an archive whose contents disagree with the
 * name the user chose. The failure path is checked for its exit code too, since a script that pipes
 * this into a backup rotation needs a non-zero status when nothing was written.
 */
class ConfigExportTest : FunSpec({

	val archive: ConfigArchiveUseCase = mockk()

	extension(
		KoinExtension(
			module { single { archive } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(archive) }

	val tmpDir = tempdir()
	val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

	/**
	 * Run `config export` against a fresh file named [fileName] inside the spec's temp directory.
	 */
	suspend fun exportTo(fileName: String, vararg args: String): File {
		val target = File(tmpDir, fileName)
		Omnisign().test(listOf("config", "export", target.absolutePath, *args)).statusCode shouldBe 0
		return target
	}

	test("infers the archive format from the output file extension") {
		coEvery { archive.exportGlobal(any()) } returns bytes.right()

		exportTo("config.yaml")

		coVerify { archive.exportGlobal(ConfigFormat.YAML) }
	}

	test("accepts the alternate yml spelling") {
		coEvery { archive.exportGlobal(any()) } returns bytes.right()

		exportTo("config.yml")

		coVerify { archive.exportGlobal(ConfigFormat.YAML) }
	}

	test("lets an explicit format override the extension") {
		coEvery { archive.exportGlobal(any()) } returns bytes.right()

		exportTo("config.yaml", "--format", "XML")

		coVerify { archive.exportGlobal(ConfigFormat.XML) }
	}

	test("falls back to JSON for an unrecognized extension") {
		coEvery { archive.exportGlobal(any()) } returns bytes.right()

		exportTo("config.zip")

		coVerify { archive.exportGlobal(ConfigFormat.JSON) }
	}

	test("exports only the global section by default") {
		coEvery { archive.exportGlobal(any()) } returns bytes.right()

		val result = Omnisign().test(
			listOf("config", "export", File(tmpDir, "global.zip").absolutePath),
		)

		coVerify(exactly = 0) { archive.exportApp(any()) }
		result.stdout shouldContain "global"
	}

	test("exports the whole application configuration on --all") {
		coEvery { archive.exportApp(any()) } returns bytes.right()

		val result = Omnisign().test(
			listOf("config", "export", File(tmpDir, "full.zip").absolutePath, "--all"),
		)

		coVerify { archive.exportApp(ConfigFormat.JSON) }
		coVerify(exactly = 0) { archive.exportGlobal(any()) }
		result.stdout shouldContain "full application"
	}

	test("writes the archive bytes to the requested path") {
		coEvery { archive.exportGlobal(any()) } returns bytes.right()

		val target = exportTo("written.zip")

		target.toPath().readBytes().toList() shouldBe bytes.toList()
	}

	test("reports a failure and exits non-zero without writing a file") {
		coEvery { archive.exportGlobal(any()) } returns
			ConfigurationError.saveFailed(details = "trust store unreadable").left()
		val target = File(tmpDir, "unwritten.zip")

		val result = Omnisign().test(listOf("config", "export", target.absolutePath))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Export failed"
		result.stderr shouldContain "trust store unreadable"
		target.exists() shouldBe false
	}
})
