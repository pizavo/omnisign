package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

/**
 * Unit tests for [SettingsViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : FunSpec({

    val configRepository: ConfigRepository = mockk()
    val credentialStore: CredentialStore = mockk(relaxed = true)
    val getConfig = GetConfigUseCase(configRepository)
    val setGlobalConfig = SetGlobalConfigUseCase(configRepository)
    val testDispatcher = StandardTestDispatcher()

    val baseGlobal = GlobalConfig(
        defaultHashAlgorithm = HashAlgorithm.SHA256,
        defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
    )
    val baseConfig = AppConfig(global = baseGlobal)

    beforeTest {
        clearMocks(configRepository, credentialStore)
        Dispatchers.setMain(testDispatcher)
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("load populates state from current global config") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            val state = vm.state.value
            state.defaultHashAlgorithm shouldBe HashAlgorithm.SHA256
            state.defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_B
            state.defaultEncryptionAlgorithm shouldBe null
            state.error.shouldBeNull()
        }
    }

    test("load surfaces error when config loading fails") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns ConfigurationError.InvalidConfiguration(
                message = "corrupt file"
            ).left()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("updateState applies transformation to current state") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA512) }

            vm.state.value.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
        }
    }

    test("save persists updated global config and invokes onSuccess") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA384) }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            saved.captured.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA384
            vm.state.value.saving shouldBe false
            vm.state.value.error.shouldBeNull()
        }
    }

    test("save surfaces error when disabling the default hash algorithm") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(disabledHashAlgorithms = setOf(HashAlgorithm.SHA256))
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.saving shouldBe false
            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("save stores TSA password in credential store when provided") {
        runTest(testDispatcher) {
            val globalWithTsa = baseGlobal.copy(
                timestampServer = TimestampServerConfig(url = "https://tsa.example.com"),
            )
            val configWithTsa = AppConfig(global = globalWithTsa)

            coEvery { configRepository.loadConfig() } returns configWithTsa.right()
            coEvery { configRepository.getCurrentConfig() } returns configWithTsa
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    timestampUsername = "user1",
                    timestampPassword = "secret123",
                )
            }

            vm.save()
            advanceUntilIdle()

            verify { credentialStore.setPassword("omnisign-tsa", "user1", "secret123") }
        }
    }

    test("save does not store password when username is blank") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampPassword = "secret",
                )
            }

            vm.save()
            advanceUntilIdle()

            verify(exactly = 0) { credentialStore.setPassword(any(), any(), any()) }
        }
    }

    test("load detects stored TSA password via credential store") {
        runTest(testDispatcher) {
            val globalWithCreds = baseGlobal.copy(
                timestampServer = TimestampServerConfig(
                    url = "https://tsa.example.com",
                    username = "admin",
                    credentialKey = "admin",
                ),
            )
            coEvery { configRepository.loadConfig() } returns AppConfig(global = globalWithCreds).right()
            every { credentialStore.getPassword("omnisign-tsa", "admin") } returns "stored-pw"

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.state.value.hasStoredPassword shouldBe true
        }
    }

    test("save persists trusted certificates in global validation config") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val cert = TrustedCertificateConfig(
                name = "my-ca",
                type = TrustedCertificateType.CA,
                certificateBase64 = "AAAA",
                subjectDN = "CN=My CA",
            )

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(trustedCertificates = listOf(cert)) }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            saved.captured.global.validation.trustedCertificates shouldHaveSize 1
            saved.captured.global.validation.trustedCertificates.first().name shouldBe "my-ca"
        }
    }

    test("load populates trusted certificates from existing global config") {
        runTest(testDispatcher) {
            val cert = TrustedCertificateConfig(
                name = "existing-ca",
                type = TrustedCertificateType.ANY,
                certificateBase64 = "BBBB",
                subjectDN = "CN=Existing CA",
            )
            val globalWithCerts = baseGlobal.copy(
                validation = ValidationConfig(trustedCertificates = listOf(cert)),
            )
            coEvery { configRepository.loadConfig() } returns AppConfig(global = globalWithCerts).right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.state.value.trustedCertificates shouldHaveSize 1
            vm.state.value.trustedCertificates.first().name shouldBe "existing-ca"
        }
    }

    test("hasChanges is false right after load") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.hasChanges.value shouldBe false
        }
    }

    test("hasChanges becomes true when a field is modified") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA512) }
            advanceUntilIdle()

            vm.hasChanges.value shouldBe true
        }
    }

    test("hasChanges reverts to false when field is restored to original value") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA512) }
            advanceUntilIdle()
            vm.hasChanges.value shouldBe true

            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA256) }
            advanceUntilIdle()
            vm.hasChanges.value shouldBe false
        }
    }
})

