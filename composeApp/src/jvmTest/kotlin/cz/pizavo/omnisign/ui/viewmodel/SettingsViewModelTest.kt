package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.*
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.port.SchedulerPort
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
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
    val schedulerPort: SchedulerPort = mockk(relaxed = true)
    val getConfig = GetConfigUseCase(configRepository)
    val setGlobalConfig = SetGlobalConfigUseCase(configRepository)
    val testDispatcher = StandardTestDispatcher()

    val baseGlobal = GlobalConfig(
        defaultHashAlgorithm = HashAlgorithm.SHA256,
        defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
    )
    val baseConfig = AppConfig(global = baseGlobal)

    beforeTest {
        clearMocks(configRepository, credentialStore, schedulerPort)
        Dispatchers.setMain(testDispatcher)
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("load populates state from current global config") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            val state = vm.state.value
            state.defaultHashAlgorithm shouldBe HashAlgorithm.SHA256
            state.addSignatureTimestamp shouldBe false
            state.addArchivalTimestamp shouldBe false
            state.defaultEncryptionAlgorithm shouldBe null
            state.error.shouldBeNull()
        }
    }

    test("load surfaces error when config loading fails") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns ConfigurationError.InvalidConfiguration(
                message = "corrupt file"
            ).left()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("updateState applies transformation to current state") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.trustedCertificates shouldHaveSize 1
            vm.state.value.trustedCertificates.first().name shouldBe "existing-ca"
        }
    }

    test("hasChanges is false right after load") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.hasChanges.value shouldBe false
        }
    }

    test("hasChanges becomes true when a field is modified") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
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

    test("save persists custom trusted lists in global validation config") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val tl = CustomTrustedListConfig(
                name = "my-tl",
                source = "https://example.com/tl.xml",
                signingCertPath = "/path/to/cert.pem",
            )

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(customTrustedLists = listOf(tl)) }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            saved.captured.global.validation.customTrustedLists shouldHaveSize 1
            saved.captured.global.validation.customTrustedLists.first().name shouldBe "my-tl"
            saved.captured.global.validation.customTrustedLists.first().signingCertPath shouldBe "/path/to/cert.pem"
        }
    }

    test("load populates custom trusted lists from existing global config") {
        runTest(testDispatcher) {
            val tl = CustomTrustedListConfig(
                name = "existing-tl",
                source = "https://example.com/existing.xml",
            )
            val globalWithTls = baseGlobal.copy(
                validation = ValidationConfig(customTrustedLists = listOf(tl)),
            )
            coEvery { configRepository.loadConfig() } returns AppConfig(global = globalWithTls).right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.customTrustedLists shouldHaveSize 1
            vm.state.value.customTrustedLists.first().name shouldBe "existing-tl"
        }
    }

    test("load populates renewal jobs from AppConfig") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            val configWithJobs = baseConfig.copy(renewalJobs = mapOf("nightly" to job))
            coEvery { configRepository.loadConfig() } returns configWithJobs.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.renewalJobs shouldHaveSize 1
            vm.state.value.renewalJobs.first().name shouldBe "nightly"
            vm.state.value.renewalJobs.first().globs shouldBe listOf("/docs/**/*.pdf")
        }
    }

    test("load populates available profiles from AppConfig") {
        runTest(testDispatcher) {
            val configWithProfiles = baseConfig.copy(
                profiles = mapOf(
                    "work" to ProfileConfig(name = "work"),
                    "personal" to ProfileConfig(name = "personal"),
                ),
            )
            coEvery { configRepository.loadConfig() } returns configWithProfiles.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.availableProfiles shouldBe listOf("personal", "work")
        }
    }

    test("save persists renewal jobs via ConfigRepository") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = mutableListOf<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            val job = RenewalJob(name = "archive", globs = listOf("/archive/**/*.pdf"), renewalBufferDays = 60)
            vm.updateState { it.copy(renewalJobs = listOf(job)) }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            val lastSave = saved.last()
            lastSave.renewalJobs.size shouldBe 1
            lastSave.renewalJobs["archive"]?.renewalBufferDays shouldBe 60
        }
    }

    test("save removes renewal jobs that were deleted in the edit state") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "old-job", globs = listOf("/old/**"))
            val configWithJob = baseConfig.copy(renewalJobs = mapOf("old-job" to job))
            coEvery { configRepository.loadConfig() } returns configWithJob.right()
            coEvery { configRepository.getCurrentConfig() } returns configWithJob
            val saved = mutableListOf<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.renewalJobs shouldHaveSize 1
            vm.updateState { it.copy(renewalJobs = emptyList()) }

            vm.save(onSuccess = {})
            advanceUntilIdle()

            val lastSave = saved.last()
            lastSave.renewalJobs.size shouldBe 0
        }
    }

    test("hasChanges detects renewal job additions") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.hasChanges.value shouldBe false

            val job = RenewalJob(name = "new-job", globs = listOf("/new/**"))
            vm.updateState { it.copy(renewalJobs = listOf(job)) }
            advanceUntilIdle()

            vm.hasChanges.value shouldBe true
        }
    }

    test("hasChanges reverts to false when renewal jobs are restored") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            val job = RenewalJob(name = "tmp", globs = listOf("/tmp/**"))
            vm.updateState { it.copy(renewalJobs = listOf(job)) }
            advanceUntilIdle()
            vm.hasChanges.value shouldBe true

            vm.updateState { it.copy(renewalJobs = emptyList()) }
            advanceUntilIdle()
            vm.hasChanges.value shouldBe false
        }
    }

    test("load populates scheduler fields from AppConfig") {
        runTest(testDispatcher) {
            val config = baseConfig.copy(
                schedulerConfig = SchedulerConfig(
                    cliExecutablePath = "/usr/bin/omnisign",
                    runAtHour = 3,
                    runAtMinute = 30,
                    logFilePath = "/var/log/omnisign.log",
                ),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            every { schedulerPort.isInstalled() } returns true

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerCliPath shouldBe "/usr/bin/omnisign"
            vm.state.value.schedulerHour shouldBe "3"
            vm.state.value.schedulerMinute shouldBe "30"
            vm.state.value.schedulerLogFile shouldBe "/var/log/omnisign.log"
            vm.state.value.schedulerInstalled shouldBe true
        }
    }

    test("save installs scheduler using auto-detected path when renewal jobs exist") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            every { schedulerPort.isInstalled() } returns false andThen true

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstalled shouldBe false

            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            vm.updateState {
                it.copy(
                    renewalJobs = listOf(job),
                    schedulerHour = "4",
                    schedulerMinute = "15",
                )
            }

            vm.save(onSuccess = {})
            advanceUntilIdle()

            verify { schedulerPort.install("/opt/omnisign/bin/OmniSign", 4, 15, null) }
            vm.state.value.schedulerInstalled shouldBe true
        }
    }

    test("save installs scheduler using manual path when auto-detection is unavailable") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            every { schedulerPort.isInstalled() } returns false andThen true

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            vm.updateState {
                it.copy(
                    renewalJobs = listOf(job),
                    schedulerCliPath = "/usr/bin/omnisign",
                    schedulerHour = "4",
                    schedulerMinute = "15",
                )
            }

            vm.save(onSuccess = {})
            advanceUntilIdle()

            verify { schedulerPort.install("/usr/bin/omnisign", 4, 15, null) }
            vm.state.value.schedulerInstalled shouldBe true
        }
    }

    test("save uninstalls scheduler when renewal jobs are empty") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "old", globs = listOf("/old/**"))
            val config = baseConfig.copy(
                renewalJobs = mapOf("old" to job),
                schedulerConfig = SchedulerConfig(cliExecutablePath = "/usr/bin/omnisign"),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            every { schedulerPort.isInstalled() } returns true andThen false

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstalled shouldBe true

            vm.updateState { it.copy(renewalJobs = emptyList()) }

            vm.save(onSuccess = {})
            advanceUntilIdle()

            verify { schedulerPort.uninstall() }
            verify(exactly = 0) { schedulerPort.install(any(), any(), any(), any()) }
            vm.state.value.schedulerInstalled shouldBe false
        }
    }

    test("save does not call scheduler when schedulerPort is null") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, null, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            vm.updateState {
                it.copy(
                    renewalJobs = listOf(job),
                    schedulerCliPath = "/usr/bin/omnisign",
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
        }
    }

    test("save persists scheduler config via ConfigRepository") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = mutableListOf<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    schedulerHour = "5",
                    schedulerMinute = "45",
                    schedulerLogFile = "/tmp/renewal.log",
                )
            }

            vm.save(onSuccess = {})
            advanceUntilIdle()

            val lastSave = saved.last()
            lastSave.schedulerConfig.cliExecutablePath shouldBe "/opt/omnisign/bin/OmniSign"
            lastSave.schedulerConfig.runAtHour shouldBe 5
            lastSave.schedulerConfig.runAtMinute shouldBe 45
            lastSave.schedulerConfig.logFilePath shouldBe "/tmp/renewal.log"
        }
    }

    test("save uninstalls scheduler when no executable path is available even with renewal jobs") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            vm.updateState {
                it.copy(
                    renewalJobs = listOf(job),
                    schedulerCliPath = "",
                )
            }

            vm.save(onSuccess = {})
            advanceUntilIdle()

            verify { schedulerPort.uninstall() }
            verify(exactly = 0) { schedulerPort.install(any(), any(), any(), any()) }
        }
    }

    test("load sets schedulerAutoDetectedPath when autoDetectedExecutablePath is available") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerAutoDetectedPath shouldBe "/opt/omnisign/bin/OmniSign"
            vm.state.value.effectiveSchedulerExecutablePath shouldBe "/opt/omnisign/bin/OmniSign"
        }
    }

    test("load leaves schedulerAutoDetectedPath null when autoDetectedExecutablePath is unavailable") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerAutoDetectedPath.shouldBeNull()
        }
    }

    test("effectiveSchedulerExecutablePath prefers auto-detected over persisted manual path") {
        runTest(testDispatcher) {
            val config = baseConfig.copy(
                schedulerConfig = SchedulerConfig(cliExecutablePath = "/usr/bin/omnisign"),
            )
            coEvery { configRepository.loadConfig() } returns config.right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerAutoDetectedPath shouldBe "/opt/omnisign/bin/OmniSign"
            vm.state.value.schedulerCliPath shouldBe "/usr/bin/omnisign"
            vm.state.value.effectiveSchedulerExecutablePath shouldBe "/opt/omnisign/bin/OmniSign"
        }
    }

    test("isSchedulerHourValid rejects values outside 0-23") {
        val state = GlobalConfigEditState(schedulerHour = "24")
        state.isSchedulerHourValid shouldBe false

        GlobalConfigEditState(schedulerHour = "25").isSchedulerHourValid shouldBe false
        GlobalConfigEditState(schedulerHour = "99").isSchedulerHourValid shouldBe false
        GlobalConfigEditState(schedulerHour = "0").isSchedulerHourValid shouldBe true
        GlobalConfigEditState(schedulerHour = "23").isSchedulerHourValid shouldBe true
        GlobalConfigEditState(schedulerHour = "12").isSchedulerHourValid shouldBe true
        GlobalConfigEditState(schedulerHour = "").isSchedulerHourValid shouldBe true
    }

    test("isSchedulerMinuteValid rejects values outside 0-59") {
        GlobalConfigEditState(schedulerMinute = "60").isSchedulerMinuteValid shouldBe false
        GlobalConfigEditState(schedulerMinute = "99").isSchedulerMinuteValid shouldBe false
        GlobalConfigEditState(schedulerMinute = "0").isSchedulerMinuteValid shouldBe true
        GlobalConfigEditState(schedulerMinute = "59").isSchedulerMinuteValid shouldBe true
        GlobalConfigEditState(schedulerMinute = "30").isSchedulerMinuteValid shouldBe true
        GlobalConfigEditState(schedulerMinute = "").isSchedulerMinuteValid shouldBe true
    }

    test("save blocks with error when scheduler hour is out of range") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(schedulerHour = "25") }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.error.shouldNotBeNull()
            vm.state.value.saving shouldBe false
        }
    }

    test("save blocks with error when scheduler minute is out of range") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(schedulerMinute = "60") }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.error.shouldNotBeNull()
            vm.state.value.saving shouldBe false
        }
    }

    test("save surfaces scheduler install error and does not dismiss dialog") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            every { schedulerPort.install(any(), any(), any(), any()) } throws
                    IllegalStateException("schtasks failed (exit 1): Access is denied.")
            every { schedulerPort.isInstalled() } returns false

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            vm.updateState { it.copy(renewalJobs = listOf(job)) }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.error.shouldNotBeNull()
            vm.state.value.error shouldBe "Failed to install OS scheduler: schtasks failed (exit 1): Access is denied."
            vm.state.value.schedulerInstalled shouldBe false
            vm.state.value.saving shouldBe false
        }
    }
})

