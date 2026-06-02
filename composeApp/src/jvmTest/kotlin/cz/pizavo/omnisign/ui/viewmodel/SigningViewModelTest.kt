package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.*
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.LoadFileCertificatesUseCase
import cz.pizavo.omnisign.domain.usecase.SignDocumentUseCase
import cz.pizavo.omnisign.domain.usecase.UnlockTokenUseCase
import cz.pizavo.omnisign.ui.model.PdfDocumentInfo
import cz.pizavo.omnisign.ui.model.SigningDialogState
import cz.pizavo.omnisign.ui.toast.ToastService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.time.Instant

/**
 * Unit tests for [SigningViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SigningViewModelTest : FunSpec({

	val signingRepository = mockk<SigningRepository>()
	val configRepository = mockk<ConfigRepository>()
	val idleDiscoveryFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
	val tokenService = mockk<TokenService>().also {
		every { it.discoveryRunning } returns idleDiscoveryFlow
	}
	val signUseCase = SignDocumentUseCase(signingRepository)
	val listCertsUseCase = ListCertificatesUseCase(signingRepository)
	val unlockTokenUseCase = UnlockTokenUseCase(signingRepository)
	val loadFileCertsUseCase = LoadFileCertificatesUseCase(signingRepository)
	val testDispatcher = StandardTestDispatcher()

	fun sampleDoc(filePath: String? = "/tmp/test.pdf", name: String = "test.pdf"): PdfDocumentInfo =
		PdfDocumentInfo(name = name, data = ByteArray(0), pageCount = 1, filePath = filePath)

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
		val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
		vm.state.value.shouldBeInstanceOf<SigningDialogState.Idle>()
	}

	test("open with allowTimestamping=false blocks when the config level requires a timestamp") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { configRepository.getCurrentConfig() } returns AppConfig(
				global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA),
				profiles = emptyMap(),
			)

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc(), allowTimestamping = false)
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.TimestampingUnavailable>()
			state.requiredLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
			state.profileName.shouldBeNull()
		}
	}

	test("open with allowTimestamping=false names the active profile that mandates a timestamp") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { configRepository.getCurrentConfig() } returns AppConfig(
				global = GlobalConfig(),
				profiles = mapOf(
					"archival" to ProfileConfig(name = "archival", signatureLevel = SignatureLevel.PADES_BASELINE_LTA),
				),
				activeProfile = "archival",
			)

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc(), allowTimestamping = false)
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.TimestampingUnavailable>()
			state.requiredLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
			state.profileName shouldBe "archival"
		}
	}

	test("open with allowTimestamping=false still opens the form for a B-B config") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { configRepository.getCurrentConfig() } returns AppConfig(
				global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B),
				profiles = emptyMap(),
			)

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc(), allowTimestamping = false)
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.addSignatureTimestamp shouldBe false
			state.addArchivalTimestamp shouldBe false
			state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
		}
	}

	test("open with allowTimestamping=true keeps the config-derived B-LTA timestamps") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { configRepository.getCurrentConfig() } returns AppConfig(
				global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA),
				profiles = emptyMap(),
			)

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc(), allowTimestamping = true)
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.addSignatureTimestamp shouldBe true
			state.addArchivalTimestamp shouldBe true
			state.effectiveSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
		}
	}

	test("open transitions to Ready with discovered certificates") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.certificates shouldHaveSize 1
			state.certificates.first().alias shouldBe "test-cert"
			state.selectedAlias.shouldBeNull()
			state.outputPath shouldContain "-signed"
			state.configHashAlgorithm shouldBe HashAlgorithm.SHA256
		}
	}

	test("open transitions to Ready with token warnings") {
		runTest(testDispatcher) {
			val warning = TokenDiscoveryWarning(
				tokenId = "t1", tokenName = "Broken", message = "Access denied",
			)
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(
						certificates = listOf(sampleCert),
						tokenWarnings = listOf(warning),
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.tokenWarnings shouldHaveSize 1
		}
	}

	test("open transitions to Error when certificate listing fails") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					SigningError.TokenAccessError(message = "Failed").left()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Error>()
			state.message shouldBe "Failed"
		}
	}

	test("updateState modifies Ready state") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(reason = "Test reason") }

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.reason shouldBe "Test reason"
		}
	}

	test("sign transitions to Success on successful signing") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-B",
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(selectedAlias = "test-cert") }
			vm.sign()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Success>()
			state.outputFile shouldBe "/tmp/test-signed.pdf"
			state.signatureId shouldBe "sig-1"
		}
	}

	test("sign transitions to Error on signing failure") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningError.SigningFailed(message = "Signing error", details = "bad key").left()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(selectedAlias = "test-cert") }
			vm.sign()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Error>()
			state.message shouldBe "Signing error"
			state.details shouldBe "bad key"
		}
	}

	test("dismiss resets state to Idle") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
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
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
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
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LT",
						annotatedWarnings = listOf(AnnotatedWarning("Revocation data missing")),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()
			vm.updateState { it.copy(selectedAlias = "test-cert") }
			vm.sign()
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.RevocationWarning>()
			state.warnings shouldHaveSize 1
			state.outputFile shouldBe "/tmp/test-signed.pdf"
		}
	}

	test("sign transitions to Success when revocation warnings present at B-B") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-B",
						annotatedWarnings = listOf(AnnotatedWarning("Revocation data missing")),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(selectedAlias = "test-cert", addSignatureTimestamp = false, addArchivalTimestamp = false) }
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
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LT",
						annotatedWarnings = listOf(AnnotatedWarning("Revocation data missing")),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()
			vm.updateState { it.copy(selectedAlias = "test-cert") }
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
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LT",
						annotatedWarnings = listOf(AnnotatedWarning("Revocation data missing")),
						hasRevocationWarnings = true,
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()
			vm.updateState { it.copy(selectedAlias = "test-cert") }
			vm.sign()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.RevocationWarning>()
			vm.abortAfterRevocationWarning()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
		}
	}

	test("pendingRenewalOffer is populated after LTA signing with addToRenewalJob checked") {
		runTest(testDispatcher) {
			val ltaConfig = AppConfig(
				global = GlobalConfig(
					defaultSignatureLevel = cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel.PADES_BASELINE_LTA,
				),
				profiles = mapOf("prod" to ProfileConfig(name = "prod")),
				activeProfile = "prod",
			)
			coEvery { configRepository.getCurrentConfig() } returns ltaConfig
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, assigner, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(selectedAlias = "test-cert", addToRenewalJob = true) }
			vm.sign()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.Success>()
			val offer = vm.pendingRenewalOffer.value
			offer.shouldNotBeNull()
			offer.outputFile shouldBe "/tmp/test-signed.pdf"
			offer.availableProfiles shouldBe listOf("prod")
		}
	}

	test("pendingRenewalOffer is null when addToRenewalJob is not checked") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-B",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, assigner, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(selectedAlias = "test-cert") }
			vm.sign()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.Success>()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}

	test("covered output path auto-checks addToRenewalJob and skips offer dialog") {
		runTest(testDispatcher) {
			val existingJob = RenewalJob(
				name = "archive",
				globs = listOf("/tmp/**/*.pdf"),
				profile = "prod",
			)
			val ltaConfig = AppConfig(
				global = GlobalConfig(
					defaultSignatureLevel = cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel.PADES_BASELINE_LTA,
				),
				profiles = mapOf("prod" to ProfileConfig(name = "prod")),
				activeProfile = "prod",
				renewalJobs = mapOf("archive" to existingJob),
			)
			coEvery { configRepository.getCurrentConfig() } returns ltaConfig
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, assigner, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			val ready = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			ready.addToRenewalJob shouldBe true
			ready.coveringRenewalJobName shouldBe "archive"

			vm.updateState { it.copy(selectedAlias = "test-cert") }
			vm.sign()
			advanceUntilIdle()

			vm.state.value.shouldBeInstanceOf<SigningDialogState.Success>()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}

	test("dismissRenewalOffer clears pending offer") {
		runTest(testDispatcher) {
			val ltaConfig = AppConfig(
				global = GlobalConfig(
					defaultSignatureLevel = cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel.PADES_BASELINE_LTA,
				),
			)
			coEvery { configRepository.getCurrentConfig() } returns ltaConfig
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.signDocument(any()) } returns
					SigningResult(
						outputBytes = ByteArray(0),
						outputName = "test-signed.pdf",
						signatureId = "sig-1",
						signatureLevel = "PAdES-BASELINE-LTA",
					).right()

			val assigner = RenewalJobAssigner(configRepository)
			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, assigner, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.updateState { it.copy(selectedAlias = "test-cert", addToRenewalJob = true) }
			vm.sign()
			advanceUntilIdle()

			vm.pendingRenewalOffer.value.shouldNotBeNull()
			vm.dismissRenewalOffer()
			vm.pendingRenewalOffer.value.shouldBeNull()
		}
	}

	test("open transitions to Ready with lockedTokens from discovery") {
		runTest(testDispatcher) {
			val lockedToken = LockedTokenInfo(
				tokenId = "pkcs11-1", tokenName = "Smart Card", tokenTypeName = "PKCS11",
			)
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(
						certificates = listOf(sampleCert),
						lockedTokens = listOf(lockedToken),
					).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.lockedTokens shouldHaveSize 1
			state.lockedTokens.first().tokenId shouldBe "pkcs11-1"
			state.certificates shouldHaveSize 1
		}
	}

	test("unlockToken merges certificates and removes locked entry") {
		runTest(testDispatcher) {
			val lockedToken = LockedTokenInfo(
				tokenId = "pkcs11-1", tokenName = "Smart Card", tokenTypeName = "PKCS11",
			)
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(
						certificates = listOf(sampleCert),
						lockedTokens = listOf(lockedToken),
					).right()
			val unlockedCert = AvailableCertificateInfo(
				alias = "smartcard-cert",
				subjectDN = "CN=SC",
				issuerDN = "CN=SC-CA",
				validFrom = Instant.parse("2025-01-01T00:00:00Z"),
				validTo = Instant.parse("2027-01-01T00:00:00Z"),
				tokenType = "PKCS11",
				keyUsages = listOf("digitalSignature"),
			)
			coEvery { signingRepository.unlockToken("pkcs11-1") } returns listOf(unlockedCert).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.unlockToken("pkcs11-1")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.lockedTokens shouldHaveSize 0
			state.certificates shouldHaveSize 2
			state.certificates.any { it.alias == "smartcard-cert" } shouldBe true
		}
	}

	test("unlockToken failure adds warning and keeps locked entry for retry") {
		runTest(testDispatcher) {
			val lockedToken = LockedTokenInfo(
				tokenId = "pkcs11-1", tokenName = "Smart Card", tokenTypeName = "PKCS11",
			)
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(
						certificates = listOf(sampleCert),
						lockedTokens = listOf(lockedToken),
					).right()
			coEvery { signingRepository.unlockToken("pkcs11-1") } returns
					SigningError.TokenAccessError(message = "PIN cancelled").left()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.unlockToken("pkcs11-1")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.lockedTokens shouldHaveSize 1
			state.lockedTokens.first().tokenId shouldBe "pkcs11-1"
			state.tokenWarnings shouldHaveSize 1
			state.tokenWarnings.first().message shouldBe "PIN cancelled"
		}
	}

	test("unlockToken retry after failure replaces warning and succeeds") {
		runTest(testDispatcher) {
			val lockedToken = LockedTokenInfo(
				tokenId = "pkcs11-1", tokenName = "Smart Card", tokenTypeName = "PKCS11",
			)
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(
						certificates = listOf(sampleCert),
						lockedTokens = listOf(lockedToken),
					).right()
			coEvery { signingRepository.unlockToken("pkcs11-1") } returns
					SigningError.TokenAccessError(message = "Wrong PIN").left()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.unlockToken("pkcs11-1")
			advanceUntilIdle()

			var state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.lockedTokens shouldHaveSize 1
			state.tokenWarnings shouldHaveSize 1
			state.tokenWarnings.first().message shouldBe "Wrong PIN"

			val unlockedCert = AvailableCertificateInfo(
				alias = "smartcard-cert",
				subjectDN = "CN=SC",
				issuerDN = "CN=SC-CA",
				validFrom = Instant.parse("2025-01-01T00:00:00Z"),
				validTo = Instant.parse("2027-01-01T00:00:00Z"),
				tokenType = "PKCS11",
				keyUsages = listOf("digitalSignature"),
			)
			coEvery { signingRepository.unlockToken("pkcs11-1") } returns listOf(unlockedCert).right()

			vm.unlockToken("pkcs11-1")
			advanceUntilIdle()

			state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.lockedTokens shouldHaveSize 0
			state.tokenWarnings shouldHaveSize 0
			state.certificates shouldHaveSize 2
			state.certificates.any { it.alias == "smartcard-cert" } shouldBe true
		}
	}

	test("loadPkcs12File merges certificates into Ready state") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			val fileCert = AvailableCertificateInfo(
				alias = "file-cert",
				subjectDN = "CN=File",
				issuerDN = "CN=File-CA",
				validFrom = Instant.parse("2025-01-01T00:00:00Z"),
				validTo = Instant.parse("2027-01-01T00:00:00Z"),
				tokenType = "FILE",
				keyUsages = listOf("digitalSignature"),
			)
			coEvery { signingRepository.loadCertificatesFromFile("/tmp/cert.p12") } returns
					listOf(fileCert).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.loadPkcs12File("/tmp/cert.p12")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.certificates shouldHaveSize 2
			state.certificates.any { it.alias == "file-cert" } shouldBe true
		}
	}

	test("loadPkcs12File failure adds warning") {
		runTest(testDispatcher) {
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
			coEvery { signingRepository.loadCertificatesFromFile("/tmp/bad.p12") } returns
					SigningError.TokenAccessError(message = "Wrong password").left()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, tokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.loadPkcs12File("/tmp/bad.p12")
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.tokenWarnings shouldHaveSize 1
			state.tokenWarnings.first().message shouldBe "Wrong password"
			state.certificates shouldHaveSize 1
		}
	}

	test("discoveryRunning transitions toggle refreshing on Ready state") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also { every { it.discoveryRunning } returns flow }
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			flow.value = true
			advanceUntilIdle()
			vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>().refreshing shouldBe true

			flow.value = false
			advanceUntilIdle()
			vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>().refreshing shouldBe false
		}
	}

	test("rediscovery keeps refreshing true until the cert list is refreshed (no empty-state flash)") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also { every { it.discoveryRunning } returns flow }
			val refreshGate = CompletableDeferred<Unit>()
			var calls = 0
			coEvery { signingRepository.listAvailableCertificates(false) } coAnswers {
				calls++
				if (calls == 1) {
					CertificateDiscoveryResult(certificates = emptyList()).right()
				} else {
					refreshGate.await()
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()
				}
			}

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()
			vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>().refreshing shouldBe false

			flow.value = true
			advanceUntilIdle()
			vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>().refreshing shouldBe true

			flow.value = false
			advanceUntilIdle()
			val midState = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			midState.refreshing shouldBe true
			midState.certificates shouldHaveSize 0

			refreshGate.complete(Unit)
			advanceUntilIdle()
			val finalState = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			finalState.refreshing shouldBe false
			finalState.certificates shouldHaveSize 1
		}
	}

	test("auto-refresh preserves selectedAlias when the same alias is still present") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also { every { it.discoveryRunning } returns flow }
			val refreshedCert = sampleCert.copy(subjectDN = "CN=Test (refreshed)")
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right() andThen
					CertificateDiscoveryResult(certificates = listOf(refreshedCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()
			vm.updateState { it.copy(selectedAlias = "test-cert") }

			flow.value = true
			advanceUntilIdle()
			flow.value = false
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.selectedAlias shouldBe "test-cert"
			state.certificates.first().subjectDN shouldBe "CN=Test (refreshed)"
		}
	}

	test("auto-refresh clears selectedAlias when the alias vanishes from the refreshed list") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also { every { it.discoveryRunning } returns flow }
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right() andThen
					CertificateDiscoveryResult(certificates = emptyList()).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()
			vm.updateState { it.copy(selectedAlias = "test-cert") }

			flow.value = true
			advanceUntilIdle()
			flow.value = false
			advanceUntilIdle()

			val state = vm.state.value.shouldBeInstanceOf<SigningDialogState.Ready>()
			state.selectedAlias.shouldBeNull()
			state.certificates shouldHaveSize 0
		}
	}

	test("auto-refresh uses promptForLocked=false so background events do not trigger a PIN dialog") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also { every { it.discoveryRunning } returns flow }
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			flow.value = true
			flow.value = false
			advanceUntilIdle()

			io.mockk.coVerify(atLeast = 1) { signingRepository.listAvailableCertificates(false) }
			io.mockk.coVerify(exactly = 0) { signingRepository.listAvailableCertificates(true) }
		}
	}

	test("auto-refresh is skipped when the dialog is not in Ready state") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also { every { it.discoveryRunning } returns flow }

			SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, ioDispatcher = testDispatcher)
			flow.value = true
			flow.value = false
			advanceUntilIdle()
		}
	}

	test("manual rescan publishes a toast carrying the PKCS#11 entry count") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also {
				every { it.discoveryRunning } returns flow
				justRun { it.rescanTokens() }
			}
			val pkcs11Cert = AvailableCertificateInfo(
				alias = "smartcard-cert",
				subjectDN = "CN=Smart Card",
				issuerDN = "CN=PKI",
				validFrom = Instant.parse("2025-01-01T00:00:00Z"),
				validTo = Instant.parse("2027-01-01T00:00:00Z"),
				tokenType = "PKCS11",
				keyUsages = listOf("digitalSignature"),
			)
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(pkcs11Cert)).right()

			val toastService = ToastService()
			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, toastService = toastService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.rescan()
			flow.value = true
			advanceUntilIdle()
			flow.value = false
			advanceUntilIdle()
			runCurrent()

			io.mockk.coVerify(atLeast = 1) { signingRepository.listAvailableCertificates(false) }
			io.mockk.coVerify(exactly = 0) { signingRepository.listAvailableCertificates(true) }

			val toast = toastService.active.value
			toast.shouldNotBeNull()
			toast.message.text shouldContain "1 PKCS#11 entry detected"
		}
	}

	test("manual rescan with no PKCS#11 entries publishes a zero-count toast with diagnostic action") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also {
				every { it.discoveryRunning } returns flow
				justRun { it.rescanTokens() }
			}
			val windowsCert = AvailableCertificateInfo(
				alias = "windows-my-cert",
				subjectDN = "CN=Windows MY",
				issuerDN = "CN=Windows CA",
				validFrom = Instant.parse("2025-01-01T00:00:00Z"),
				validTo = Instant.parse("2027-01-01T00:00:00Z"),
				tokenType = "WINDOWS_MY",
				keyUsages = listOf("digitalSignature"),
			)
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(windowsCert)).right()

			val toastService = ToastService()
			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, toastService = toastService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			vm.rescan()
			flow.value = true
			advanceUntilIdle()
			flow.value = false
			advanceUntilIdle()
			runCurrent()

			val toast = toastService.active.value
			toast.shouldNotBeNull()
			toast.message.text shouldContain "no PKCS#11 tokens detected"
			toast.message.actionLabel shouldBe "Show diagnostic info"
		}
	}

	test("background discovery cycle without prior rescan() does not publish a toast") {
		runTest(testDispatcher) {
			val flow = MutableStateFlow(false)
			val localTokenService = mockk<TokenService>().also { every { it.discoveryRunning } returns flow }
			coEvery { signingRepository.listAvailableCertificates(false) } returns
					CertificateDiscoveryResult(certificates = listOf(sampleCert)).right()

			val toastService = ToastService()
			val vm = SigningViewModel(signUseCase, listCertsUseCase, unlockTokenUseCase, loadFileCertsUseCase, configRepository, localTokenService, toastService = toastService, ioDispatcher = testDispatcher)
			vm.open(sampleDoc())
			advanceUntilIdle()

			flow.value = true
			advanceUntilIdle()
			flow.value = false
			advanceUntilIdle()
			runCurrent()

			toastService.active.value.shouldBeNull()
		}
	}
})







