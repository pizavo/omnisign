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
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.koin.dsl.module
import java.io.File
import kotlin.io.path.fileSize

/**
 * Behavioral tests for the [Sign] command verifying stdout/stderr output
 * and exit codes with mocked dependencies.
 *
 * The secret reader accepts every file and acts as it does on Linux, so the tests of a password
 * kept in a file run on every operating system; the rules for such a file are tested with the
 * reader itself.
 */
class SignTest : FunSpec({

	val tmpDir = tempdir()

	val signingRepository: SigningRepository = mockk()
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

	/** Arguments that sign a new input with a new keystore file, both named after [name]. */
	fun keystoreSigning(name: String) = listOf(
		"sign", "-k", tmpFile("$name.p12").absolutePath,
		"-f", tmpFile("$name-input.pdf").absolutePath, "-o", tmpFile("$name-output.pdf").absolutePath,
	)

	/** Make signing succeed, and return the slot that captures what it was asked to sign with. */
	fun signsSuccessfully(): CapturingSlot<SigningParameters> = slot<SigningParameters>().also { captured ->
		coEvery { signingRepository.signDocument(capture(captured)) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = "signed.pdf",
			signatureId = "sig-env",
			signatureLevel = "PAdES-BASELINE-B",
		).right()
	}

	extension(
		KoinExtension(
			module {
				single { SignDocumentUseCase(signingRepository) }
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
	

	test("sign command should be instantiable") {
		Sign().shouldNotBeNull()
	}
	
	test("sign command name should be 'sign'") {
		Sign().commandName shouldBe "sign"
	}
	
	test("sign command registered options should include required file and output") {
		val optionNames = Sign().registeredOptions().flatMap { it.names }
		optionNames.shouldContain("--file")
		optionNames.shouldContain("-f")
		optionNames.shouldContain("--output")
		optionNames.shouldContain("-o")
	}
	
	test("sign command registered options should include signature metadata options") {
		val optionNames = Sign().registeredOptions().flatMap { it.names }
		optionNames.shouldContain("--reason")
		optionNames.shouldContain("--location")
		optionNames.shouldContain("--contact")
		optionNames.shouldContain("--certificate")
	}
	
	test("sign command registered options should include timestamp and profile overrides") {
		val optionNames = Sign().registeredOptions().flatMap { it.names }
		optionNames.shouldContain("--no-timestamp")
		optionNames.shouldContain("--profile")
	}
	
	test("sign command registered options should include visible signature options") {
		val optionNames = Sign().registeredOptions().flatMap { it.names }
		optionNames.shouldContain("--visible")
		optionNames.shouldContain("--vis-page")
		optionNames.shouldContain("--vis-x")
		optionNames.shouldContain("--vis-y")
		optionNames.shouldContain("--vis-width")
		optionNames.shouldContain("--vis-height")
		optionNames.shouldContain("--vis-text")
		optionNames.shouldContain("--vis-image")
	}
	
	test("successful sign outputs result to stdout") {
		val input = tmpFile("input.pdf")
		val output = tmpFile("output.pdf")
		
		coEvery { signingRepository.signDocument(any()) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = output.name,
			signatureId = "sig-123",
			signatureLevel = "PAdES-BASELINE-B",
		).right()
		
		val result = Omnisign().test(listOf("sign", "-c", "my-cert", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.output shouldContain "SIGNING RESULT"
		result.output shouldContain "sig-123"
		result.output shouldContain "PAdES-BASELINE-B"
		result.statusCode shouldBe 0
	}
	
	test("sign error exits with code 1 and prints to stderr") {
		val input = tmpFile("input2.pdf")
		val output = tmpFile("output2.pdf")
		
		coEvery { signingRepository.signDocument(any()) } returns SigningError.SigningFailed(
			text = LocalizableText.Literal("Token not found"),
			details = "No PKCS#11 token available",
		).left()
		
		val result = Omnisign().test(listOf("sign", "-c", "my-cert", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.statusCode shouldBe 1
		result.stderr shouldContain "Token not found"
	}
	
	test("sign with --json outputs JSON on success") {
		val input = tmpFile("input3.pdf")
		val output = tmpFile("output3.pdf")
		
		coEvery { signingRepository.signDocument(any()) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = output.name,
			signatureId = "sig-json",
			signatureLevel = "PAdES-BASELINE-T",
		).right()
		
		val result = Omnisign().test(listOf("--json", "sign", "-c", "my-cert", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.output shouldContain "\"success\""
		result.output shouldContain "sig-json"
		result.statusCode shouldBe 0
	}
	
	test("sign with --json outputs JSON on error with exit code 1") {
		val input = tmpFile("input4.pdf")
		val output = tmpFile("output4.pdf")
		
		coEvery { signingRepository.signDocument(any()) } returns SigningError.SigningFailed(
			text = LocalizableText.Literal("Certificate expired"),
		).left()
		
		val result = Omnisign().test(listOf("--json", "sign", "-c", "my-cert", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.output shouldContain "\"success\""
		result.output shouldContain "Certificate expired"
		result.statusCode shouldBe 1
	}

	test("sign without --certificate or --keystore fails with a usage error") {
		val input = tmpFile("bare-input.pdf")
		val output = tmpFile("bare-output.pdf")

		val result = Omnisign().test(listOf("sign", "-f", input.absolutePath, "-o", output.absolutePath))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Specify the signing key"
	}

	test("sign with --keystore passes the keystore file to the signer and needs no --certificate") {
		val input = tmpFile("ks-input.pdf")
		val output = tmpFile("ks-output.pdf")
		val keystore = tmpFile("ks-signer.p12")
		val captured = slot<SigningParameters>()
		coEvery { signingRepository.signDocument(capture(captured)) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = output.name,
			signatureId = "sig-ks",
			signatureLevel = "PAdES-BASELINE-B",
		).right()

		val result = Omnisign().test(listOf("sign", "-k", keystore.absolutePath, "-f", input.absolutePath, "-o", output.absolutePath))

		result.statusCode shouldBe 0
		captured.captured.keystoreFile.shouldNotBeNull()
		captured.captured.certificateAlias shouldBe null
	}

	test("sign reads the keystore password from OMNISIGN_KEYSTORE_PASSWORD") {
		val input = tmpFile("env-ks-input.pdf")
		val output = tmpFile("env-ks-output.pdf")
		val keystore = tmpFile("env-ks-signer.p12")
		val captured = slot<SigningParameters>()
		coEvery { signingRepository.signDocument(capture(captured)) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = output.name,
			signatureId = "sig-env-ks",
			signatureLevel = "PAdES-BASELINE-B",
		).right()

		val result = Omnisign().test(
			listOf("sign", "-k", keystore.absolutePath, "-f", input.absolutePath, "-o", output.absolutePath),
			envvars = mapOf("OMNISIGN_KEYSTORE_PASSWORD" to "env-secret")
		)

		result.statusCode shouldBe 0
		captured.captured.keystorePassword?.value shouldBe "env-secret"
	}

	test("a --keystore-password on the command line wins over OMNISIGN_KEYSTORE_PASSWORD") {
		val input = tmpFile("cli-ks-input.pdf")
		val output = tmpFile("cli-ks-output.pdf")
		val keystore = tmpFile("cli-ks-signer.p12")
		val captured = slot<SigningParameters>()
		coEvery { signingRepository.signDocument(capture(captured)) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = output.name,
			signatureId = "sig-cli-ks",
			signatureLevel = "PAdES-BASELINE-B",
		).right()

		val result = Omnisign().test(
			listOf(
				"sign", "-k", keystore.absolutePath, "--keystore-password", "cli-secret",
				"-f", input.absolutePath, "-o", output.absolutePath
			),
			envvars = mapOf("OMNISIGN_KEYSTORE_PASSWORD" to "env-secret")
		)

		result.statusCode shouldBe 0
		captured.captured.keystorePassword?.value shouldBe "cli-secret"
	}

	test("sign reads the timestamp password from OMNISIGN_TIMESTAMP_PASSWORD") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig.copy(
			global = defaultConfig.global.copy(
				timestampServer = TimestampServerConfig(url = "https://tsa.example.test", username = "tsa-user")
			)
		)
		val input = tmpFile("env-tsa-input.pdf")
		val output = tmpFile("env-tsa-output.pdf")
		val captured = slot<SigningParameters>()
		coEvery { signingRepository.signDocument(capture(captured)) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = output.name,
			signatureId = "sig-env-tsa",
			signatureLevel = "PAdES-BASELINE-B",
		).right()

		val result = Omnisign().test(
			listOf("sign", "-c", "my-cert", "-f", input.absolutePath, "-o", output.absolutePath),
			envvars = mapOf("OMNISIGN_TIMESTAMP_PASSWORD" to "tsa-secret")
		)

		result.statusCode shouldBe 0
		captured.captured.resolvedConfig?.timestampServer?.runtimePassword?.value shouldBe "tsa-secret"
	}

	test("sign reads the timestamp password from the file OMNISIGN_TIMESTAMP_PASSWORD_FILE names") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig.copy(
			global = defaultConfig.global.copy(
				timestampServer = TimestampServerConfig(url = "https://tsa.example.test", username = "tsa-user")
			)
		)
		val captured = signsSuccessfully()
		val passwordFile = tmpFile("timestamp-password").also { it.writeText("tsa-file-secret\n") }

		val result = Omnisign().test(
			listOf("sign", "-c", "my-cert", "-f", tmpFile("file-tsa-input.pdf").absolutePath, "-o", tmpFile("file-tsa-output.pdf").absolutePath),
			envvars = mapOf("OMNISIGN_TIMESTAMP_PASSWORD_FILE" to passwordFile.absolutePath)
		)

		result.statusCode shouldBe 0
		captured.captured.resolvedConfig?.timestampServer?.runtimePassword?.value shouldBe "tsa-file-secret"
	}

	test("sign reads the keystore password from the file OMNISIGN_KEYSTORE_PASSWORD_FILE names") {
		val captured = signsSuccessfully()
		val passwordFile = tmpFile("keystore-password").also { it.writeText("file-secret\n") }

		val result = Omnisign().test(
			keystoreSigning("file-ks"),
			envvars = mapOf("OMNISIGN_KEYSTORE_PASSWORD_FILE" to passwordFile.absolutePath)
		)

		result.statusCode shouldBe 0
		captured.captured.keystorePassword?.value shouldBe "file-secret"
	}

	test("sign refuses a keystore password set both in OMNISIGN_KEYSTORE_PASSWORD and in a file") {
		signsSuccessfully()
		val passwordFile = tmpFile("both-keystore-password").also { it.writeText("file-secret") }

		val result = Omnisign().test(
			keystoreSigning("both-ks"),
			envvars = mapOf(
				"OMNISIGN_KEYSTORE_PASSWORD" to "env-secret",
				"OMNISIGN_KEYSTORE_PASSWORD_FILE" to passwordFile.absolutePath,
			)
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Configuration Error: Both OMNISIGN_KEYSTORE_PASSWORD and " +
				"OMNISIGN_KEYSTORE_PASSWORD_FILE are set. Set only one of them."
	}

	test("a --keystore-password on the command line wins over a file in the environment, which is not read") {
		val captured = signsSuccessfully()
		val missing = File(tmpDir, "missing-keystore-password").absolutePath

		val result = Omnisign().test(
			keystoreSigning("cli-over-file-ks") + listOf("--keystore-password", "cli-secret"),
			envvars = mapOf("OMNISIGN_KEYSTORE_PASSWORD_FILE" to missing)
		)

		result.statusCode shouldBe 0
		captured.captured.keystorePassword?.value shouldBe "cli-secret"
	}

	test("sign takes '-' in OMNISIGN_KEYSTORE_PASSWORD as the password instead of prompting for one") {
		val captured = signsSuccessfully()

		val result = Omnisign().test(keystoreSigning("dash-ks"), envvars = mapOf("OMNISIGN_KEYSTORE_PASSWORD" to "-"))

		result.statusCode shouldBe 0
		captured.captured.keystorePassword?.value shouldBe "-"
	}

	test("sign reads no keystore password from the environment without --keystore") {
		val captured = signsSuccessfully()
		val missing = File(tmpDir, "unused-keystore-password").absolutePath

		val result = Omnisign().test(
			listOf("sign", "-c", "my-cert", "-f", tmpFile("token-input.pdf").absolutePath, "-o", tmpFile("token-output.pdf").absolutePath),
			envvars = mapOf("OMNISIGN_KEYSTORE_PASSWORD_FILE" to missing)
		)

		result.statusCode shouldBe 0
		captured.captured.keystorePassword shouldBe null
	}

	test("sign ignores a keystore password in the variable named after the command path") {
		val captured = signsSuccessfully()

		val result = Omnisign().test(
			keystoreSigning("inferred-ks"),
			envvars = mapOf("OMNISIGN_SIGN_KEYSTORE_PASSWORD" to "inferred-secret")
		)

		result.statusCode shouldBe 0
		captured.captured.keystorePassword shouldBe null
	}

	test("sign with --json reports a refused keystore password file as JSON") {
		signsSuccessfully()
		val missing = File(tmpDir, "json-missing-keystore-password").absolutePath

		val result = Omnisign().test(
			listOf("--json") + keystoreSigning("json-ks"),
			envvars = mapOf("OMNISIGN_KEYSTORE_PASSWORD_FILE" to missing)
		)

		result.statusCode shouldBe 1
		result.stdout shouldContain "\"success\""
		result.stdout shouldContain "named by OMNISIGN_KEYSTORE_PASSWORD_FILE does not exist."
	}

	test("sign infers the variable name of other options from the command path") {
		val input = tmpFile("env-url-input.pdf")
		val output = tmpFile("env-url-output.pdf")
		val captured = slot<SigningParameters>()
		coEvery { signingRepository.signDocument(capture(captured)) } returns SigningResult(
			outputBytes = ByteArray(0),
			outputName = output.name,
			signatureId = "sig-env-url",
			signatureLevel = "PAdES-BASELINE-B",
		).right()

		val result = Omnisign().test(
			listOf("sign", "-c", "my-cert", "-f", input.absolutePath, "-o", output.absolutePath),
			envvars = mapOf("OMNISIGN_SIGN_TIMESTAMP_URL" to "https://tsa.example.test")
		)

		result.statusCode shouldBe 0
		captured.captured.resolvedConfig?.timestampServer?.url shouldBe "https://tsa.example.test"
	}
})

