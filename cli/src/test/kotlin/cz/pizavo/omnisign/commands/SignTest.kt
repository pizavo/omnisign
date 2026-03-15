package cz.pizavo.omnisign.commands

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.SigningResult
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
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module
import java.io.File

/**
 * Behavioral tests for the [Sign] command verifying stdout/stderr output
 * and exit codes with mocked dependencies.
 */
class SignTest : FunSpec({
	
	val tmpDir = tempdir()
	
	val signingRepository: SigningRepository = mockk()
	val configRepository: ConfigRepository = mockk()
	
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
				single { SignDocumentUseCase(signingRepository) }
				single { configRepository }
				single<PasswordCallback> { mockk() }
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
			outputFile = output.absolutePath,
			signatureId = "sig-123",
			signatureLevel = "PAdES-BASELINE-B",
		).right()
		
		val result = Omnisign().test(listOf("sign", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.output shouldContain "SIGNING RESULT"
		result.output shouldContain "sig-123"
		result.output shouldContain "PAdES-BASELINE-B"
		result.statusCode shouldBe 0
	}
	
	test("sign error exits with code 1 and prints to stderr") {
		val input = tmpFile("input2.pdf")
		val output = tmpFile("output2.pdf")
		
		coEvery { signingRepository.signDocument(any()) } returns SigningError.SigningFailed(
			message = "Token not found",
			details = "No PKCS#11 token available",
		).left()
		
		val result = Omnisign().test(listOf("sign", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.statusCode shouldBe 1
		result.stderr shouldContain "Token not found"
	}
	
	test("sign with --json outputs JSON on success") {
		val input = tmpFile("input3.pdf")
		val output = tmpFile("output3.pdf")
		
		coEvery { signingRepository.signDocument(any()) } returns SigningResult(
			outputFile = output.absolutePath,
			signatureId = "sig-json",
			signatureLevel = "PAdES-BASELINE-T",
		).right()
		
		val result = Omnisign().test(listOf("--json", "sign", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.output shouldContain "\"success\""
		result.output shouldContain "sig-json"
		result.statusCode shouldBe 0
	}
	
	test("sign with --json outputs JSON on error with exit code 1") {
		val input = tmpFile("input4.pdf")
		val output = tmpFile("output4.pdf")
		
		coEvery { signingRepository.signDocument(any()) } returns SigningError.SigningFailed(
			message = "Certificate expired",
		).left()
		
		val result = Omnisign().test(listOf("--json", "sign", "-f", input.absolutePath, "-o", output.absolutePath))
		
		result.output shouldContain "\"success\""
		result.output shouldContain "Certificate expired"
		result.statusCode shouldBe 1
	}
})

