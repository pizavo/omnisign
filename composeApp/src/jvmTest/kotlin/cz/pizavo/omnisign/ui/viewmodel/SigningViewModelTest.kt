package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.ui.model.SigningDialogState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Instant

/**
 * Unit tests for [SigningViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SigningViewModelTest : FunSpec({

	val signingRepository = mockk<SigningRepository>()
	val configRepository = mockk<ConfigRepository>()
	val signUseCase = SignDocumentUseCase(signingRepository)
	val listCertsUseCase = ListCertificatesUseCase(signingRepository)
	val testDispatcher = StandardTestDispatcher()

	val sampleCert = AvailableCertificateInfo(
		alias = "test-cert",
		subjectDN = "CN=Test",
		issuerDN = "CN=Issuer",
		validFrom = Instant.parse("2025-01-01T00:00:00Z"),
		validTo = Instant.parse("2027-01-01T00:00:00Z"),
		tokenType = "PKCS12",
		keyUsages = listOf("digitalSignature"),
	)

	val appConfig = AppConfig(
		global = GlobalConfig(),
		profiles = emptyMap(),
	)

	beforeEach {
		coEvery { configRepository.getCurrentConfig() } returns appConfig
		Dispatchers.setMain(testDispatcher)
	}

	afterEach {
		Dispatchers.resetMain()
	}

	test("initial state is Idle") {
		val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
		vm.state.value.shouldBeInstanceOf<SigningDialogState.Idle>()
	}

	test("open transitions to Ready with discovered certificates") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.certificates shouldHaveSize 1
			state.certificates.first().alias shouldBe "test-cert"
			state.outputPath shouldContain "-signed"
			state.configHashAlgorithm shouldBe HashAlgorithm.SHA256
		}
	}

	test("open transitions to Ready with token warnings") {
		runTest(testDispatcher) {
			val warning = TokenDiscoveryWarning(
				tokenId = "t1", tokenName = "Broken", message = "Access denied",
			)
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(
						certificates = listOf(sampleCert),
						tokenWarnings = listOf(warning),
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.tokenWarnings shouldHaveSize 1
		}
	}

	test("open transitions to Error when certificate listing fails") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates() } returns
					SigningError.TokenAccessError(message = "Failed").left()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Error>()
			state.message shouldBe "Failed"
		}
	}

	test("updateState modifies Ready state") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(reason = "Test reason") }

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.reason shouldBe "Test reason"
		}
	}

	test("sign transitions to Success on successful signing") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputFile = "/tmp/test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-B",
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			vm.sign()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Success>()
			state.outputFile shouldBe "/tmp/test-signed.pdf"
			state.signatureId shouldBe "sig-1"
		}
	}

	test("sign transitions to Error on signing failure") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningError.SigningFailed(message = "Signing error", details = "bad key").left()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			vm.sign()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Error>()
			state.message shouldBe "Signing error"
			state.details shouldBe "bad key"
		}
	}

	test("dismiss resets state to Idle") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			vm.dismiss()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.Idle>()
		}
	}

	test("buildSuggestedOutputPath inserts suffix before extension") {
		SigningViewModel.buildSuggestedOutputPath("/tmp/doc.pdf", "-signed") shouldBe "/tmp/doc-signed.pdf"
	}

	test("buildSuggestedOutputPath handles no extension") {
		SigningViewModel.buildSuggestedOutputPath("/tmp/doc", "-signed") shouldBe "/tmp/doc-signed"
	}

	test("open derives addSignatureTimestamp from config level B-LT") {
		runTest(testDispatcher) {
			val ltConfig = AppConfig(
				global = GlobalConfig(
					defaultSignatureLevel = cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel.PADES_BASELINE_LT,
				),
			)
			coEvery { configRepository.getCurrentConfig() } returns ltConfig
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.addSignatureTimestamp shouldBe true
			state.addArchivalTimestamp shouldBe false
		}
	}

	test("sign transitions to RevocationWarning when revocation warnings present at B-LT") {
		runTest(testDispatcher) {
			val ltConfig = AppConfig(
				global = GlobalConfig(
					defaultSignatureLevel = cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel.PADES_BASELINE_LT,
				),
			)
			coEvery { configRepository.getCurrentConfig() } returns ltConfig
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputFile = "/tmp/test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LT",
						warnings = listOf("Revocation data missing"),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()
			vm.sign()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.RevocationWarning>()
			state.warnings shouldHaveSize 1
			state.outputFile shouldBe "/tmp/test-signed.pdf"
		}
	}

	test("sign transitions to Success when revocation warnings present at B-B") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputFile = "/tmp/test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-B",
						warnings = listOf("Revocation data missing"),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()

			vm.updateState { it.copy(addSignatureTimestamp = false, addArchivalTimestamp = false) }
			vm.sign()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.Success>()
		}
	}

	test("acceptRevocationWarning transitions to Success") {
		runTest(testDispatcher) {
			val ltConfig = AppConfig(
				global = GlobalConfig(
					defaultSignatureLevel = cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel.PADES_BASELINE_LT,
				),
			)
			coEvery { configRepository.getCurrentConfig() } returns ltConfig
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputFile = "/tmp/test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LT",
						warnings = listOf("Revocation data missing"),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()
			vm.sign()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.RevocationWarning>()
			vm.acceptRevocationWarning()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Success>()
			state.outputFile shouldBe "/tmp/test-signed.pdf"
		}
	}

	test("abortAfterRevocationWarning restores Ready state") {
		runTest(testDispatcher) {
			val ltConfig = AppConfig(
				global = GlobalConfig(
					defaultSignatureLevel = cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel.PADES_BASELINE_LT,
				),
			)
			coEvery { configRepository.getCurrentConfig() } returns ltConfig
			coEvery { signingRepository.listAvailableCertificates() } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputFile = "/tmp/test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LT",
						warnings = listOf("Revocation data missing"),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, configRepository, testDispatcher)
			vm.open("/tmp/test.pdf")
			advanceUntilIdle()
			vm.sign()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.RevocationWarning>()
			vm.abortAfterRevocationWarning()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
		}
	}
})





