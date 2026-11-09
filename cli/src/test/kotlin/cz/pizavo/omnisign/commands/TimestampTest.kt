package cz.pizavo.omnisign.commands

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.data.secret.SecretFileFacts
import cz.pizavo.omnisign.data.secret.SecretFileReader
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.ExtendDocumentUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.koin.dsl.module
import java.io.File
import kotlin.io.path.fileSize

/**
 * Behavioral tests for the [Timestamp] command verifying stdout/stderr output,
 * exit codes, and JSON mode.
 */
class TimestampTest : FunSpec({

	val tmpDir = tempdir()

	val archivingRepository: ArchivingRepository = mockk()
	val configRepository: ConfigRepository = mockk()
	val secretReader = EnvironmentSecretReader(
		SecretFileReader(
			probe = { SecretFileFacts(isRegularFile = true, size = it.fileSize(), ownerUid = 1000, mode = 0b110_000_000, fileSystemType = "tmpfs") },
			effectiveUid = { 1000 },
		),
		osName = "Linux",
	)

	val defaultConfig = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
		)
	)

	fun tmpFile(name: String) = File(tmpDir, name).also { it.createNewFile() }

	extension(
		KoinExtension(
			module {
				single { ExtendDocumentUseCase(archivingRepository) }
				single { configRepository }
				single<PasswordCallback> { mockk() }
				single { secretReader }
			},
			mode = KoinLifecycleMode.Test
		)
	)

	beforeTest {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig
	}

	test("timestamp command should be instantiable") {
		Timestamp().shouldNotBeNull()
	}

	test("timestamp command name should be 'timestamp'") {
		Timestamp().commandName shouldBe "timestamp"
	}

	test("timestamp command registered options should include file and output") {
		val optionNames = Timestamp().registeredOptions().flatMap { it.names }
		optionNames.shouldContain("--file")
		optionNames.shouldContain("-f")
		optionNames.shouldContain("--output")
		optionNames.shouldContain("-o")
	}

	test("timestamp command registered options should include level and profile") {
		val optionNames = Timestamp().registeredOptions().flatMap { it.names }
		optionNames.shouldContain("--level")
		optionNames.shouldContain("-l")
		optionNames.shouldContain("--profile")
	}

	test("successful timestamp outputs result to stdout") {
		val input = tmpFile("input.pdf")
		val output = tmpFile("output.pdf")

		coEvery { archivingRepository.extendDocument(any()) } returns ArchivingResult(
			outputBytes = ByteArray(0), outputName = output.name,
			newSignatureLevel = "PAdES-BASELINE-T",
		).right()

		val result = Omnisign().test(listOf("timestamp", "-f", input.absolutePath, "-o", output.absolutePath))

		result.output shouldContain "TIMESTAMP RESULT"
		result.output shouldContain "PAdES-BASELINE-T"
		result.statusCode shouldBe 0
	}

	test("timestamp error exits with code 1 and prints to stderr") {
		val input = tmpFile("input2.pdf")
		val output = tmpFile("output2.pdf")

		coEvery { archivingRepository.extendDocument(any()) } returns ArchivingError.TimestampFailed(
			text = LocalizableText.Literal("TSA unreachable"),
			details = "Connection refused",
		).left()

		val result = Omnisign().test(listOf("timestamp", "-f", input.absolutePath, "-o", output.absolutePath))

		result.statusCode shouldBe 1
		result.stderr shouldContain "❌ Extension Error: TSA unreachable"
		result.stderr shouldContain "Details: Connection refused"
	}

	test("timestamp with --json outputs JSON on success") {
		val input = tmpFile("input3.pdf")
		val output = tmpFile("output3.pdf")

		coEvery { archivingRepository.extendDocument(any()) } returns ArchivingResult(
			outputBytes = ByteArray(0), outputName = output.name,
			newSignatureLevel = "PAdES-BASELINE-LTA",
		).right()

		val result = Omnisign().test(listOf("--json", "timestamp", "-f", input.absolutePath, "-o", output.absolutePath))

		result.output shouldContain "\"success\""
		result.output shouldContain "PAdES-BASELINE-LTA"
		result.statusCode shouldBe 0
	}

	test("timestamp with --json outputs JSON on error with exit code 1") {
		val input = tmpFile("input4.pdf")
		val output = tmpFile("output4.pdf")

		coEvery { archivingRepository.extendDocument(any()) } returns ArchivingError.ExtensionFailed(
			text = LocalizableText.Literal("Extension not possible"),
		).left()

		val result = Omnisign().test(listOf("--json", "timestamp", "-f", input.absolutePath, "-o", output.absolutePath))

		result.output shouldContain "\"success\""
		result.output shouldContain "Extension not possible"
		result.statusCode shouldBe 1
	}

	test("timestamp reads the timestamp password from OMNISIGN_TIMESTAMP_PASSWORD") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig.copy(
			global = defaultConfig.global.copy(
				timestampServer = TimestampServerConfig(url = "https://tsa.example.test", username = "tsa-user")
			)
		)
		val input = tmpFile("env-input.pdf")
		val output = tmpFile("env-output.pdf")
		val captured = slot<ArchivingParameters>()
		coEvery { archivingRepository.extendDocument(capture(captured)) } returns ArchivingResult(
			outputBytes = ByteArray(0), outputName = output.name,
			newSignatureLevel = "PAdES-BASELINE-T",
		).right()

		val result = Omnisign().test(
			listOf("timestamp", "-f", input.absolutePath, "-o", output.absolutePath),
			envvars = mapOf("OMNISIGN_TIMESTAMP_PASSWORD" to "tsa-secret")
		)

		result.statusCode shouldBe 0
		captured.captured.resolvedConfig?.timestampServer?.runtimePassword?.value shouldBe "tsa-secret"
	}

	test("timestamp reads the timestamp password from the file OMNISIGN_TIMESTAMP_PASSWORD_FILE names") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig.copy(
			global = defaultConfig.global.copy(
				timestampServer = TimestampServerConfig(url = "https://tsa.example.test", username = "tsa-user")
			)
		)
		val input = tmpFile("file-env-input.pdf")
		val output = tmpFile("file-env-output.pdf")
		val passwordFile = tmpFile("timestamp-password").also { it.writeText("tsa-file-secret\n") }
		val captured = slot<ArchivingParameters>()
		coEvery { archivingRepository.extendDocument(capture(captured)) } returns ArchivingResult(
			outputBytes = ByteArray(0), outputName = output.name,
			newSignatureLevel = "PAdES-BASELINE-T",
		).right()

		val result = Omnisign().test(
			listOf("timestamp", "-f", input.absolutePath, "-o", output.absolutePath),
			envvars = mapOf("OMNISIGN_TIMESTAMP_PASSWORD_FILE" to passwordFile.absolutePath)
		)

		result.statusCode shouldBe 0
		captured.captured.resolvedConfig?.timestampServer?.runtimePassword?.value shouldBe "tsa-file-secret"
	}

	test("timestamp refuses a timestamp password set both in OMNISIGN_TIMESTAMP_PASSWORD and in a file") {
		val input = tmpFile("both-env-input.pdf")
		val output = tmpFile("both-env-output.pdf")
		val passwordFile = tmpFile("both-timestamp-password").also { it.writeText("tsa-file-secret") }

		val result = Omnisign().test(
			listOf("timestamp", "-f", input.absolutePath, "-o", output.absolutePath),
			envvars = mapOf(
				"OMNISIGN_TIMESTAMP_PASSWORD" to "tsa-secret",
				"OMNISIGN_TIMESTAMP_PASSWORD_FILE" to passwordFile.absolutePath,
			)
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Configuration Error: Both OMNISIGN_TIMESTAMP_PASSWORD and " +
				"OMNISIGN_TIMESTAMP_PASSWORD_FILE are set. Set only one of them."
	}
})

