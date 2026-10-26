package cz.pizavo.omnisign.commands.config.profile

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
 * Behavioral tests for the [ProfileExport] command: which configuration format ends up inside the
 * archive, and what a failed export leaves behind.
 *
 * The format follows `--format`, then the output file's extension, then JSON, as for
 * `config export`. A failed export writes no file and exits with status 1, so a backup script never
 * goes on as if the archive were there.
 */
class ProfileExportTest : FunSpec({

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

	test("writes the archive to the requested path, with JSON inside by default") {
		coEvery { archive.exportProfile(any(), any()) } returns bytes.right()
		val target = File(tmpDir, "qualified.zip")

		val result = Omnisign().test(listOf("config", "profile", "export", "qualified", target.absolutePath))

		result.statusCode shouldBe 0
		coVerify { archive.exportProfile("qualified", ConfigFormat.JSON) }
		target.toPath().readBytes().toList() shouldBe bytes.toList()
	}

	test("infers the format inside the archive from the output file extension") {
		coEvery { archive.exportProfile(any(), any()) } returns bytes.right()

		Omnisign().test(listOf("config", "profile", "export", "qualified", File(tmpDir, "qualified.yaml").absolutePath))

		coVerify { archive.exportProfile("qualified", ConfigFormat.YAML) }
	}

	test("lets an explicit format override the extension") {
		coEvery { archive.exportProfile(any(), any()) } returns bytes.right()

		Omnisign().test(
			listOf(
				"config", "profile", "export", "qualified",
				File(tmpDir, "explicit.yaml").absolutePath,
				"--format", "XML",
			),
		)

		coVerify { archive.exportProfile("qualified", ConfigFormat.XML) }
	}

	test("reports a failure and exits non-zero without writing a file") {
		coEvery { archive.exportProfile(any(), any()) } returns ConfigurationError.profileNotFound("ghost").left()
		val target = File(tmpDir, "ghost.zip")

		val result = Omnisign().test(listOf("config", "profile", "export", "ghost", target.absolutePath))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Export failed: Profile 'ghost' does not exist"
		target.exists() shouldBe false
	}
})
