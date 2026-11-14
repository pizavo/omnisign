package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.domain.model.config.*
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.result.RenewalRunOutcome
import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import cz.pizavo.omnisign.domain.port.SchedulerPort
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import cz.pizavo.omnisign.ui.model.PendingTrustedCert
import cz.pizavo.omnisign.ui.model.SettingsError
import kotlin.time.Instant
import cz.pizavo.omnisign.ui.platform.loadUseNativeTitleBar
import cz.pizavo.omnisign.ui.platform.saveUseNativeTitleBar
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

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

    val configDir = tempdir()

    /**
     * A dispatcher that runs every block at once on the calling thread and sets [running] while it
     * does, so a stub can tell whether it was called on this dispatcher or on the main one.
     */
    class InPlaceDispatcher : CoroutineDispatcher() {
        /** Whether this dispatcher is running a block right now. */
        var running = false

        /** Run [block] at once, with [running] set while it does. */
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val outer = running
            running = true
            try {
                block.run()
            } finally {
                running = outer
            }
        }
    }

    beforeTest {
        clearMocks(configRepository, credentialStore, schedulerPort)
        every { credentialStore.isPersistent } returns true
        every { credentialStore.setPassword(any(), any(), any()) } returns Unit.right()
        every { schedulerPort.isAvailable() } returns true
        Dispatchers.setMain(testDispatcher)
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("load populates the most recent renewal run record") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            val recordStore: RenewalRunRecordStore = mockk()
            val record = RenewalRunRecord(
                lastRunAt = Instant.fromEpochSeconds(1_700_000_000),
                outcome = RenewalRunOutcome.SUCCESS,
                lastSuccessAt = Instant.fromEpochSeconds(1_700_000_000),
            )
            every { recordStore.load() } returns record

            val vm = SettingsViewModel(
                getConfig,
                setGlobalConfig,
                credentialStore = credentialStore,
                renewalRunRecordStore = recordStore,
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.renewalRunRecord shouldBe record
        }
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
                LocalizableText.Literal("corrupt file")
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

    test("save applies the dialog to the configuration as it is saved, keeping the expiry overrides the CLI set and stamping a level change") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            val editedMeanwhile = baseConfig.copy(
                global = baseGlobal.copy(
                    validation = ValidationConfig(
                        algorithmConstraints = AlgorithmConstraintsConfig(
                            policyUpdateDate = "2026-11-12",
                            expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01"),
                        ),
                    ),
                ),
            )
            coEvery { configRepository.getCurrentConfig() } returns editedMeanwhile
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(
                getConfig,
                setGlobalConfig,
                credentialStore = credentialStore,
                ioDispatcher = testDispatcher,
                today = { LocalDate(2026, 11, 14) },
            )
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(algoExpirationLevel = AlgorithmConstraintLevel.WARN) }
            vm.save()
            advanceUntilIdle()

            val constraints = saved.captured.global.validation.algorithmConstraints
            constraints.expirationDateOverrides shouldBe mapOf("RIPEMD160" to "2030-01-01")
            constraints.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
            constraints.policyUpdateDate shouldBe "2026-11-14"
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
                    timestampPassword = Sensitive("secret123"),
                )
            }

            vm.save()
            advanceUntilIdle()

            verify { credentialStore.setPassword("omnisign-tsa", "user1", "secret123") }
        }
    }

    test("save shows the error and saves nothing when the credential store does not keep the TSA password") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            val failure = ConfigurationError.passwordStoreFailed("The keyring is locked")
            every { credentialStore.setPassword(any(), any(), any()) } returns failure.left()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                    defaultHashAlgorithm = HashAlgorithm.SHA384,
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            coVerify(exactly = 0) { configRepository.saveConfig(any()) }
            vm.state.value.saving shouldBe false
            vm.state.value.error shouldBe SettingsError.Domain(failure.text)
        }
    }

    test("save clears the TSA password from the form once the credential store keeps it") {
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
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            vm.state.value.timestampPassword shouldBe Sensitive("")
            vm.state.value.hasStoredPassword shouldBe true
            vm.hasChanges.value shouldBe false
        }
    }

    test("an edit made while the save runs stays in the form, unsaved, and keeps the dialog open") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = mutableListOf<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()
            val savedOcspTimeout = vm.state.value.ocspTimeout

            vm.updateState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            vm.updateState { it.copy(ocspTimeout = "9000") }
            advanceUntilIdle()

            successCalled shouldBe false
            saved.first().global.ocsp.timeout.toString() shouldBe savedOcspTimeout
            vm.state.value.ocspTimeout shouldBe "9000"
            vm.state.value.timestampPassword shouldBe Sensitive("")
            vm.state.value.saving shouldBe false
            vm.hasChanges.value shouldBe true
        }
    }

    test("save keeps the TSA password in the form when the username is changed while the save runs") {
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
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            vm.updateState { it.copy(timestampUsername = "user2") }
            advanceUntilIdle()

            successCalled shouldBe false
            verify { credentialStore.setPassword("omnisign-tsa", "user1", "secret123") }
            vm.state.value.timestampUsername shouldBe "user2"
            vm.state.value.timestampPassword shouldBe Sensitive("secret123")
            vm.hasChanges.value shouldBe true
        }
    }

    test("certificate changes staged while the save runs stay pending, unsaved") {
        runTest(testDispatcher) {
            val trustStore: TrustStore = mockk()
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()
            coEvery { trustStore.remove(TrustScope.Global, "sha256-old") } returns Unit.right()
            coEvery {
                trustStore.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem")
            } returns TrustedCertificate(
                fingerprint = "sha256-new",
                subjectDN = "CN=new",
                notBefore = Instant.parse("2024-01-01T00:00:00Z"),
                notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                type = TrustedCertificateType.CA,
            ).right()
            val savedAdd = PendingTrustedCert(
                source = "ca.pem",
                type = TrustedCertificateType.CA,
                bytes = byteArrayOf(1, 2, 3),
                fingerprint = "sha256-new",
                subjectDN = "CN=new",
                notAfter = Instant.parse("2030-01-01T00:00:00Z"),
            )
            val laterAdd = PendingTrustedCert(
                source = "other.pem",
                type = TrustedCertificateType.CA,
                bytes = byteArrayOf(4, 5, 6),
                fingerprint = "sha256-other",
                subjectDN = "CN=other",
                notAfter = Instant.parse("2031-01-01T00:00:00Z"),
            )

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore,
                ioDispatcher = testDispatcher, trustStore = trustStore,
            )
            vm.load()
            advanceUntilIdle()
            vm.updateState {
                it.copy(pendingTrustedCertRemovals = setOf("sha256-old"), pendingTrustedCertAdds = listOf(savedAdd))
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            vm.updateState {
                it.copy(
                    pendingTrustedCertRemovals = it.pendingTrustedCertRemovals + "sha256-older",
                    pendingTrustedCertAdds = it.pendingTrustedCertAdds + laterAdd,
                )
            }
            advanceUntilIdle()

            successCalled shouldBe false
            coVerify(exactly = 0) { trustStore.remove(TrustScope.Global, "sha256-older") }
            coVerify(exactly = 0) { trustStore.add(TrustScope.Global, any(), any(), "other.pem") }
            vm.state.value.pendingTrustedCertAdds shouldBe listOf(laterAdd)
            vm.state.value.pendingTrustedCertRemovals shouldBe setOf("sha256-older")
            vm.hasChanges.value shouldBe true
        }
    }

    test("save writes a staleness threshold below 1 day as 1 while its warning is off") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = mutableListOf<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(stalenessNotificationEnabled = false, stalenessThresholdDays = "0") }
            vm.save()
            advanceUntilIdle()

            vm.state.value.error.shouldBeNull()
            saved.last().schedulerConfig.stalenessThresholdDays shouldBe 1
        }
    }

    test("save keeps a TSA password changed while the save runs, as an unsaved change") {
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
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            vm.updateState { it.copy(timestampPassword = Sensitive("secret456")) }
            advanceUntilIdle()

            successCalled shouldBe false
            verify { credentialStore.setPassword("omnisign-tsa", "user1", "secret123") }
            vm.state.value.timestampPassword shouldBe Sensitive("secret456")
            vm.state.value.hasStoredPassword shouldBe true
            vm.hasChanges.value shouldBe true
        }
    }

    test("save shows the error, leaves the scheduler alone and keeps the dialog open when the renewal jobs cannot be written") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val failure = ConfigurationError.saveFailed("The disk is full")
            coEvery { configRepository.saveConfig(any()) } returnsMany listOf(Unit.right(), failure.left())

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
            coVerify(exactly = 2) { configRepository.saveConfig(any()) }
            verify(exactly = 0) { schedulerPort.install(any(), any(), any(), any()) }
            verify(exactly = 0) { schedulerPort.uninstall() }
            vm.state.value.error shouldBe SettingsError.Domain(failure.text)
            vm.state.value.saving shouldBe false
            vm.hasChanges.value shouldBe true
        }
    }

    test("save refuses a field out of its range, showing its error and saving nothing") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()
            val loaded = vm.state.value

            listOf<Pair<(GlobalConfigEditState) -> GlobalConfigEditState, SettingsError>>(
                { s: GlobalConfigEditState -> s.copy(pkcs11ProbeTimeout = "500") } to SettingsError.Pkcs11ProbeTimeoutInvalid,
                { s: GlobalConfigEditState -> s.copy(trustedListRefreshInterval = "0") } to
                        SettingsError.TrustedListRefreshIntervalInvalid,
                { s: GlobalConfigEditState -> s.copy(stalenessNotificationEnabled = true, stalenessThresholdDays = "0") } to
                        SettingsError.StalenessThresholdInvalid,
            ).forEach { (edit, expected) ->
                vm.updateState { edit(loaded) }

                var successCalled = false
                vm.save(onSuccess = { successCalled = true })
                advanceUntilIdle()

                successCalled shouldBe false
                vm.state.value.error shouldBe expected
                vm.state.value.saving shouldBe false
            }
            coVerify(exactly = 0) { configRepository.saveConfig(any()) }
        }
    }

    test("save stores the TSA password on the IO dispatcher, and saves only after it") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            val keyringDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = keyringDispatcher)
            vm.updateState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            vm.save()
            advanceUntilIdle()

            verify(exactly = 0) { credentialStore.setPassword(any(), any(), any()) }
            coVerify(exactly = 0) { configRepository.saveConfig(any()) }

            keyringDispatcher.scheduler.advanceUntilIdle()
            advanceUntilIdle()

            verify { credentialStore.setPassword("omnisign-tsa", "user1", "secret123") }
            coVerify { configRepository.saveConfig(any()) }
        }
    }

    test("load reads the stored TSA password on the IO dispatcher") {
        runTest(testDispatcher) {
            val globalWithCreds = baseGlobal.copy(
                timestampServer = TimestampServerConfig(
                    url = "https://tsa.example.com",
                    username = "admin",
                    credentialKey = "admin",
                ),
            )
            coEvery { configRepository.loadConfig() } returns AppConfig(global = globalWithCreds).right()
            every { credentialStore.getPassword("omnisign-tsa", "admin") } returns "stored-secret"
            val keyringDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = keyringDispatcher)
            vm.load()
            advanceUntilIdle()

            verify(exactly = 0) { credentialStore.getPassword(any(), any()) }

            keyringDispatcher.scheduler.advanceUntilIdle()

            verify { credentialStore.getPassword("omnisign-tsa", "admin") }
        }
    }

    test("save refuses a TSA password entered without a username, storing and saving nothing") {
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
                    timestampPassword = Sensitive("secret"),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.error shouldBe SettingsError.TsaPasswordNeedsUsername
            vm.state.value.timestampPassword shouldBe Sensitive("secret")
            verify(exactly = 0) { credentialStore.setPassword(any(), any(), any()) }
            coVerify(exactly = 0) { configRepository.saveConfig(any()) }
        }
    }

    test("save refuses a TSA password the credential store would keep in memory only, and saves once it is cleared") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()
            every { credentialStore.isPersistent } returns false

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.error shouldBe SettingsError.TsaPasswordNotKept
            vm.state.value.timestampPassword shouldBe Sensitive("secret123")
            verify(exactly = 0) { credentialStore.setPassword(any(), any(), any()) }
            coVerify(exactly = 0) { configRepository.saveConfig(any()) }

            vm.updateState { it.copy(timestampPassword = Sensitive("")) }
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            saved.captured.global.timestampServer?.username shouldBe "user1"
            saved.captured.global.timestampServer?.credentialKey.shouldBeNull()
        }
    }

    test("save drops a TSA password entered for a timestamp server the save leaves out, storing nothing") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    timestampEnabled = false,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            saved.captured.global.timestampServer.shouldBeNull()
            verify(exactly = 0) { credentialStore.setPassword(any(), any(), any()) }
            vm.state.value.hasStoredPassword shouldBe false
        }
    }

    test("renaming the TSA user after a save that stored the password keeps the key the password is stored under") {
        runTest(testDispatcher) {
            var stored = baseConfig
            coEvery { configRepository.loadConfig() } answers { stored.right() }
            coEvery { configRepository.getCurrentConfig() } answers { stored }
            coEvery { configRepository.saveConfig(any()) } answers {
                stored = firstArg()
                Unit.right()
            }

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }
            vm.save()
            advanceUntilIdle()
            vm.updateState { it.copy(timestampUsername = "user2") }
            vm.save()
            advanceUntilIdle()

            verify(exactly = 1) { credentialStore.setPassword(any(), any(), any()) }
            verify { credentialStore.setPassword("omnisign-tsa", "user1", "secret123") }
            stored.global.timestampServer?.username shouldBe "user2"
            stored.global.timestampServer?.credentialKey shouldBe "user1"
        }
    }

    test("a save started while another runs does nothing") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, credentialStore = credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()
            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA384) }

            var successCount = 0
            vm.save(onSuccess = { successCount++ })
            vm.save(onSuccess = { successCount++ })
            advanceUntilIdle()

            successCount shouldBe 1
            coVerify(exactly = 1) { configRepository.saveConfig(any()) }
        }
    }

    test("save keeps what the CLI saved to the configuration file while the dialog was open") {
        runTest(testDispatcher) {
            val configFile = configDir.toPath().resolve("config.json")
            val dialogRepository = FileConfigRepository(configFile, testDispatcher)
            dialogRepository.saveConfig(baseConfig).shouldBeRight()
            val vm = SettingsViewModel(
                GetConfigUseCase(dialogRepository),
                SetGlobalConfigUseCase(dialogRepository),
                dialogRepository,
                credentialStore,
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            val cliRepository = FileConfigRepository(configFile, testDispatcher)
            val cliConfig = cliRepository.loadConfig().shouldBeRight()
            cliRepository.saveConfig(
                cliConfig.copy(
                    global = cliConfig.global.copy(
                        validation = cliConfig.global.validation.copy(
                            algorithmConstraints = AlgorithmConstraintsConfig(
                                expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01"),
                            ),
                        ),
                    ),
                    profiles = mapOf("work" to ProfileConfig(name = "work")),
                ),
            ).shouldBeRight()

            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA384) }
            vm.save()
            advanceUntilIdle()

            vm.state.value.error.shouldBeNull()
            val onDisk = FileConfigRepository(configFile, testDispatcher).loadConfig().shouldBeRight()
            onDisk.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA384
            onDisk.global.validation.algorithmConstraints.expirationDateOverrides shouldBe
                    mapOf("RIPEMD160" to "2030-01-01")
            onDisk.profiles.keys shouldBe setOf("work")
        }
    }

    test("save keeps what the CLI saved while the credential store was storing the TSA password") {
        runTest(testDispatcher) {
            val configFile = configDir.toPath().resolve("config-keyring-wait.json")
            val dialogRepository = FileConfigRepository(configFile, testDispatcher)
            dialogRepository.saveConfig(baseConfig).shouldBeRight()
            val cliRepository = FileConfigRepository(configFile, Dispatchers.Unconfined)
            every { credentialStore.setPassword(any(), any(), any()) } answers {
                runBlocking {
                    val cliConfig = cliRepository.loadConfig().shouldBeRight()
                    cliRepository.saveConfig(
                        cliConfig.copy(profiles = mapOf("work" to ProfileConfig(name = "work"))),
                    ).shouldBeRight()
                }
                Unit.right()
            }
            val vm = SettingsViewModel(
                GetConfigUseCase(dialogRepository),
                SetGlobalConfigUseCase(dialogRepository),
                dialogRepository,
                credentialStore,
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    defaultHashAlgorithm = HashAlgorithm.SHA384,
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }
            vm.save()
            advanceUntilIdle()

            vm.state.value.error.shouldBeNull()
            verify { credentialStore.setPassword("omnisign-tsa", "user1", "secret123") }
            val onDisk = FileConfigRepository(configFile, testDispatcher).loadConfig().shouldBeRight()
            onDisk.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA384
            onDisk.global.timestampServer?.credentialKey shouldBe "user1"
            onDisk.profiles.keys shouldBe setOf("work")
        }
    }

    test("save shows the error and writes nothing when the configuration cannot be read again") {
        runTest(testDispatcher) {
            val readFailure = ConfigurationError.InvalidConfiguration(LocalizableText.Literal("corrupt file"))
            coEvery { configRepository.loadConfig() } returnsMany listOf(baseConfig.right(), readFailure.left())
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()
            vm.updateState { it.copy(defaultHashAlgorithm = HashAlgorithm.SHA384) }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.error shouldBe SettingsError.Domain(readFailure.text)
            vm.state.value.saving shouldBe false
            coVerify(exactly = 0) { configRepository.saveConfig(any()) }
            verify(exactly = 0) { schedulerPort.uninstall() }
        }
    }

    test("save does not refuse a TSA password where no credential store is bound, as on the web") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()
            vm.updateState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "user1",
                    timestampPassword = Sensitive("secret123"),
                )
            }

            vm.save()
            advanceUntilIdle()

            vm.state.value.error.shouldBeNull()
            coVerify { configRepository.saveConfig(any()) }
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
                    stalenessNotificationEnabled = false,
                    stalenessThresholdDays = 21,
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
            vm.state.value.stalenessNotificationEnabled shouldBe false
            vm.state.value.stalenessThresholdDays shouldBe "21"
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
            verify(exactly = 0) { schedulerPort.isAvailable() }
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
                    stalenessNotificationEnabled = false,
                    stalenessThresholdDays = "21",
                )
            }

            vm.save(onSuccess = {})
            advanceUntilIdle()

            val lastSave = saved.last()
            lastSave.schedulerConfig.cliExecutablePath shouldBe "/opt/omnisign/bin/OmniSign"
            lastSave.schedulerConfig.runAtHour shouldBe 5
            lastSave.schedulerConfig.runAtMinute shouldBe 45
            lastSave.schedulerConfig.logFilePath shouldBe "/tmp/renewal.log"
            lastSave.schedulerConfig.stalenessNotificationEnabled shouldBe false
            lastSave.schedulerConfig.stalenessThresholdDays shouldBe 21
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
            vm.state.value.error shouldBe SettingsError.SchedulerInstallFailed("schtasks failed (exit 1): Access is denied.")
            vm.state.value.schedulerInstalled shouldBe false
            vm.state.value.saving shouldBe false
        }
    }

    test("a scheduler install failure keeps Save enabled, and saving again installs it and closes the dialog") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            var installAttempts = 0
            every { schedulerPort.install(any(), any(), any(), any()) } answers {
                installAttempts++
                if (installAttempts == 1) throw IllegalStateException("schtasks failed (exit 1): Access is denied.")
            }
            every { schedulerPort.isInstalled() } answers { installAttempts > 1 }

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
            vm.state.value.schedulerInstallPending shouldBe true
            vm.hasChanges.value shouldBe true

            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            installAttempts shouldBe 2
            vm.state.value.error.shouldBeNull()
            vm.state.value.schedulerInstallPending shouldBe false
            vm.state.value.schedulerInstalled shouldBe true
            vm.hasChanges.value shouldBe false
        }
    }

    test("a scheduler install failure stays marked when the staged certificate changes fail too") {
        runTest(testDispatcher) {
            val trustStore: TrustStore = mockk()
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()
            val failure = TrustStoreError.storageFailed("Access is denied")
            coEvery { trustStore.add(TrustScope.Global, any(), any(), any()) } returns failure.left()
            every { schedulerPort.install(any(), any(), any(), any()) } throws IllegalStateException("schtasks failed")
            every { schedulerPort.isInstalled() } returns false

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher, trustStore = trustStore,
            )
            vm.load()
            advanceUntilIdle()

            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            val staged = PendingTrustedCert(
                source = "ca.pem",
                type = TrustedCertificateType.CA,
                bytes = byteArrayOf(1, 2, 3),
                fingerprint = "sha256-new",
                subjectDN = "CN=new",
                notAfter = Instant.parse("2030-01-01T00:00:00Z"),
            )
            vm.updateState { it.copy(renewalJobs = listOf(job), pendingTrustedCertAdds = listOf(staged)) }
            vm.save()
            advanceUntilIdle()

            vm.state.value.error shouldBe SettingsError.Domain(failure.text)
            vm.state.value.schedulerInstallPending shouldBe true
        }
    }

    test("a scheduler install failure that gives no message carries no reason, for the dialog to name in its language") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            every { schedulerPort.install(any(), any(), any(), any()) } throws IllegalStateException()
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

            vm.save()
            advanceUntilIdle()

            vm.state.value.error shouldBe SettingsError.SchedulerInstallFailed(null)
        }
    }

    test("load enables Save for a scheduler job missing while renewal jobs and an executable path exist, and saving registers it") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            val configWithJobs = baseConfig.copy(renewalJobs = mapOf("nightly" to job))
            coEvery { configRepository.loadConfig() } returns configWithJobs.right()
            coEvery { configRepository.getCurrentConfig() } returns configWithJobs
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            var installed = false
            every { schedulerPort.isInstalled() } answers { installed }
            every { schedulerPort.install(any(), any(), any(), any()) } answers { installed = true }

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstallPending shouldBe true
            vm.hasChanges.value shouldBe true

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            verify(exactly = 1) { schedulerPort.install("/opt/omnisign/bin/OmniSign", any(), any(), any()) }
            vm.state.value.schedulerInstallPending shouldBe false
            vm.hasChanges.value shouldBe false
        }
    }

    test("load leaves Save disabled when the scheduler job is already registered") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            coEvery { configRepository.loadConfig() } returns baseConfig.copy(renewalJobs = mapOf("nightly" to job)).right()
            every { schedulerPort.isInstalled() } returns true

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstallPending shouldBe false
            vm.hasChanges.value shouldBe false
            verify(exactly = 0) { schedulerPort.isAvailable() }
        }
    }

    test("load leaves Save disabled when there is no renewal job to schedule") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            every { schedulerPort.isInstalled() } returns false

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstallPending shouldBe false
            vm.hasChanges.value shouldBe false
            verify(exactly = 0) { schedulerPort.isAvailable() }
        }
    }

    test("load leaves Save disabled when no executable path is known for the scheduler") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            coEvery { configRepository.loadConfig() } returns baseConfig.copy(renewalJobs = mapOf("nightly" to job)).right()
            every { schedulerPort.isInstalled() } returns false

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstallPending shouldBe false
            vm.hasChanges.value shouldBe false
            verify(exactly = 0) { schedulerPort.isAvailable() }
        }
    }

    test("load leaves Save disabled where no OS scheduler is available, as on the web") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            coEvery { configRepository.loadConfig() } returns baseConfig.copy(
                renewalJobs = mapOf("nightly" to job),
                schedulerConfig = SchedulerConfig(cliExecutablePath = "/opt/omnisign/bin/OmniSign"),
            ).right()

            val vm = SettingsViewModel(getConfig, setGlobalConfig, configRepository, credentialStore, ioDispatcher = testDispatcher)
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstallPending shouldBe false
            vm.hasChanges.value shouldBe false
        }
    }

    test("load leaves Save disabled for a missing scheduler job where the OS scheduler cannot register it, as without a systemd user instance") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            coEvery { configRepository.loadConfig() } returns baseConfig.copy(renewalJobs = mapOf("nightly" to job)).right()
            every { schedulerPort.isInstalled() } returns false
            every { schedulerPort.isAvailable() } returns false

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstallPending shouldBe false
            vm.hasChanges.value shouldBe false
            verify(exactly = 1) { schedulerPort.isAvailable() }
        }
    }

    test("load leaves Save disabled for a missing scheduler job when asking whether the OS scheduler can register it fails") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            coEvery { configRepository.loadConfig() } returns baseConfig.copy(renewalJobs = mapOf("nightly" to job)).right()
            every { schedulerPort.isInstalled() } returns false
            every { schedulerPort.isAvailable() } throws IllegalStateException("probe interrupted")

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.schedulerInstallPending shouldBe false
            vm.state.value.renewalJobs shouldBe listOf(job)
            vm.state.value.error.shouldBeNull()
            vm.hasChanges.value shouldBe false
        }
    }

    test("a scheduler install failure leaves Save disabled where the OS scheduler cannot register the job, and keeps the error shown") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            val noSystemd = "OmniSign's automatic scheduler requires systemd, but no usable user instance was found."
            every { schedulerPort.install(any(), any(), any(), any()) } throws IllegalStateException(noSystemd)
            every { schedulerPort.isInstalled() } returns false
            every { schedulerPort.isAvailable() } returns false

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
            vm.state.value.error shouldBe SettingsError.SchedulerInstallFailed(noSystemd)
            vm.state.value.schedulerInstallPending shouldBe false
            vm.state.value.renewalJobs shouldBe listOf(job)
            vm.hasChanges.value shouldBe false
            verify(exactly = 1) { schedulerPort.isAvailable() }
        }
    }

    test("load asks whether the OS scheduler can register a missing job on the IO dispatcher, as it may start a process") {
        runTest(testDispatcher) {
            val job = RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf"))
            coEvery { configRepository.loadConfig() } returns baseConfig.copy(renewalJobs = mapOf("nightly" to job)).right()
            every { schedulerPort.isInstalled() } returns false
            val io = InPlaceDispatcher()
            var askedOnIo: Boolean? = null
            every { schedulerPort.isAvailable() } answers { askedOnIo = io.running; true }

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = io,
            )
            vm.load()
            advanceUntilIdle()

            askedOnIo shouldBe true
            vm.state.value.schedulerInstallPending shouldBe true
        }
    }

    test("a scheduler install failure asks whether the OS scheduler can register the job on the IO dispatcher, as it may start a process") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            every { schedulerPort.install(any(), any(), any(), any()) } throws IllegalStateException("Access is denied")
            every { schedulerPort.isInstalled() } returns false
            val io = InPlaceDispatcher()
            var askedOnIo: Boolean? = null
            every { schedulerPort.isAvailable() } answers { askedOnIo = io.running; true }

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore, schedulerPort,
                autoDetectedExecutablePath = "/opt/omnisign/bin/OmniSign",
                ioDispatcher = io,
            )
            vm.load()
            advanceUntilIdle()

            vm.updateState { it.copy(renewalJobs = listOf(RenewalJob(name = "nightly", globs = listOf("/docs/**/*.pdf")))) }
            vm.save()
            advanceUntilIdle()

            askedOnIo shouldBe true
            vm.state.value.schedulerInstallPending shouldBe true
        }
    }

    test("load populates useNativeTitleBar and showNativeTitleBarOption when isLinuxDesktop is true") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            mockkStatic(::loadUseNativeTitleBar)
            every { loadUseNativeTitleBar() } returns true

            try {
                val vm = SettingsViewModel(
                    getConfig, setGlobalConfig, credentialStore = credentialStore,
                    isLinuxDesktop = true, ioDispatcher = testDispatcher,
                )
                vm.load()
                advanceUntilIdle()

                vm.state.value.useNativeTitleBar shouldBe true
                vm.state.value.showNativeTitleBarOption shouldBe true
            } finally {
                unmockkStatic(::loadUseNativeTitleBar)
            }
        }
    }

    test("load defaults useNativeTitleBar to false when preference returns null on Linux") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            mockkStatic(::loadUseNativeTitleBar)
            every { loadUseNativeTitleBar() } returns null

            try {
                val vm = SettingsViewModel(
                    getConfig, setGlobalConfig, credentialStore = credentialStore,
                    isLinuxDesktop = true, ioDispatcher = testDispatcher,
                )
                vm.load()
                advanceUntilIdle()

                vm.state.value.useNativeTitleBar shouldBe false
                vm.state.value.showNativeTitleBarOption shouldBe true
            } finally {
                unmockkStatic(::loadUseNativeTitleBar)
            }
        }
    }

    test("load does not set showNativeTitleBarOption when isLinuxDesktop is false") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, credentialStore = credentialStore,
                isLinuxDesktop = false, ioDispatcher = testDispatcher,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.useNativeTitleBar shouldBe false
            vm.state.value.showNativeTitleBarOption shouldBe false
        }
    }

    test("save persists native title bar preference when isLinuxDesktop is true") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            mockkStatic(::loadUseNativeTitleBar, ::saveUseNativeTitleBar)
            every { loadUseNativeTitleBar() } returns false
            every { saveUseNativeTitleBar(any()) } returns Unit

            try {
                val vm = SettingsViewModel(
                    getConfig, setGlobalConfig, configRepository, credentialStore,
                    isLinuxDesktop = true, ioDispatcher = testDispatcher,
                )
                vm.load()
                advanceUntilIdle()

                vm.updateState { it.copy(useNativeTitleBar = true) }

                vm.save(onSuccess = {})
                advanceUntilIdle()

                verify { saveUseNativeTitleBar(true) }
            } finally {
                unmockkStatic(::loadUseNativeTitleBar, ::saveUseNativeTitleBar)
            }
        }
    }

    test("save does not persist native title bar preference when isLinuxDesktop is false") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            mockkStatic(::saveUseNativeTitleBar)
            every { saveUseNativeTitleBar(any()) } returns Unit

            try {
                val vm = SettingsViewModel(
                    getConfig, setGlobalConfig, configRepository, credentialStore,
                    isLinuxDesktop = false, ioDispatcher = testDispatcher,
                )
                vm.load()
                advanceUntilIdle()

                vm.updateState { it.copy(useNativeTitleBar = true) }

                vm.save(onSuccess = {})
                advanceUntilIdle()

                verify(exactly = 0) { saveUseNativeTitleBar(any()) }
            } finally {
                unmockkStatic(::saveUseNativeTitleBar)
            }
        }
    }

    test("hasChanges detects useNativeTitleBar modification on Linux") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            mockkStatic(::loadUseNativeTitleBar)
            every { loadUseNativeTitleBar() } returns false

            try {
                val vm = SettingsViewModel(
                    getConfig, setGlobalConfig, credentialStore = credentialStore,
                    isLinuxDesktop = true, ioDispatcher = testDispatcher,
                )
                vm.load()
                advanceUntilIdle()

                vm.hasChanges.value shouldBe false

                vm.updateState { it.copy(useNativeTitleBar = true) }
                advanceUntilIdle()

                vm.hasChanges.value shouldBe true
            } finally {
                unmockkStatic(::loadUseNativeTitleBar)
            }
        }
    }

    test("canBackup is false when no archive backend is wired") {
        runTest(testDispatcher) {
            val vm = SettingsViewModel(getConfig, setGlobalConfig, ioDispatcher = testDispatcher)
            vm.canBackup shouldBe false
        }
    }

    test("buildConfigArchive returns the archive bytes from the use case") {
        runTest(testDispatcher) {
            val archive = mockk<ConfigArchivePort>()
            coEvery { archive.exportFullConfig() } returns byteArrayOf(1, 2, 3).right()
            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configArchive = archive,
                ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
            )

            vm.canBackup shouldBe true
            vm.buildConfigArchive() shouldBe byteArrayOf(1, 2, 3)
        }
    }

    test("buildConfigArchive surfaces an export error and returns null") {
        runTest(testDispatcher) {
            val archive = mockk<ConfigArchivePort>()
            coEvery { archive.exportFullConfig() } returns
                ConfigurationError.InvalidConfiguration(LocalizableText.Literal("export failed")).left()
            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configArchive = archive,
                ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
            )

            vm.buildConfigArchive().shouldBeNull()
            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("importConfiguration imports the archive and reloads the state") {
        runTest(testDispatcher) {
            val archive = mockk<ConfigArchivePort>()
            coEvery { archive.importFullConfig(any()) } returns Unit.right()
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configArchive = archive,
                ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
            )

            vm.importConfiguration(byteArrayOf(9))
            advanceUntilIdle()

            coVerify { archive.importFullConfig(byteArrayOf(9)) }
            vm.state.value.defaultHashAlgorithm shouldBe HashAlgorithm.SHA256
            vm.state.value.error.shouldBeNull()
        }
    }

    test("importConfiguration surfaces an import error") {
        runTest(testDispatcher) {
            val archive = mockk<ConfigArchivePort>()
            coEvery { archive.importFullConfig(any()) } returns
                ConfigurationError.InvalidConfiguration(LocalizableText.Literal("bad archive")).left()
            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configArchive = archive,
                ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
            )

            vm.importConfiguration(byteArrayOf(0))
            advanceUntilIdle()

            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("load populates the trusted certificate baseline from the trust store") {
        runTest(testDispatcher) {
            val trustStore: TrustStore = mockk()
            val cert = TrustedCertificate(
                fingerprint = "sha256-ca",
                subjectDN = "CN=ca",
                notBefore = Instant.parse("2024-01-01T00:00:00Z"),
                notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                type = TrustedCertificateType.CA,
            )
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { trustStore.list(TrustScope.Global) } returns listOf(cert).right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, credentialStore = credentialStore,
                ioDispatcher = testDispatcher, trustStore = trustStore,
            )
            vm.load()
            advanceUntilIdle()

            vm.state.value.trustedCertificates shouldHaveSize 1
            vm.state.value.trustedCertsAvailable shouldBe true
            vm.hasChanges.value shouldBe false
        }
    }

    test("save applies staged certificate additions and removals to the trust store") {
        runTest(testDispatcher) {
            val trustStore: TrustStore = mockk()
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()
            coEvery { trustStore.remove(TrustScope.Global, "sha256-old") } returns Unit.right()
            coEvery {
                trustStore.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem")
            } returns TrustedCertificate(
                fingerprint = "sha256-new",
                subjectDN = "CN=new",
                notBefore = Instant.parse("2024-01-01T00:00:00Z"),
                notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                type = TrustedCertificateType.CA,
            ).right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore,
                ioDispatcher = testDispatcher, trustStore = trustStore,
            )
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    pendingTrustedCertRemovals = setOf("sha256-old"),
                    pendingTrustedCertAdds = listOf(
                        PendingTrustedCert(
                            source = "ca.pem",
                            type = TrustedCertificateType.CA,
                            bytes = byteArrayOf(1, 2, 3),
                            fingerprint = "sha256-new",
                            subjectDN = "CN=new",
                            notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                        ),
                    ),
                )
            }
            advanceUntilIdle()
            vm.hasChanges.value shouldBe true

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe true
            coVerify { trustStore.remove(TrustScope.Global, "sha256-old") }
            coVerify { trustStore.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem") }
            vm.state.value.pendingTrustedCertAdds.shouldBeEmpty()
            vm.state.value.pendingTrustedCertRemovals.shouldBeEmpty()
        }
    }

    test("a staged certificate the trust store refuses shows the store's error as keyed text, to render in the UI's language") {
        runTest(testDispatcher) {
            val trustStore: TrustStore = mockk()
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { configRepository.getCurrentConfig() } returns baseConfig
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()
            coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()
            val failure = TrustStoreError.storageFailed("Access is denied")
            coEvery { trustStore.add(TrustScope.Global, any(), any(), any()) } returns failure.left()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, configRepository, credentialStore,
                ioDispatcher = testDispatcher, trustStore = trustStore,
            )
            vm.load()
            advanceUntilIdle()

            vm.updateState {
                it.copy(
                    pendingTrustedCertAdds = listOf(
                        PendingTrustedCert(
                            source = "ca.pem",
                            type = TrustedCertificateType.CA,
                            bytes = byteArrayOf(1, 2, 3),
                            fingerprint = "sha256-new",
                            subjectDN = "CN=new",
                            notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                        ),
                    ),
                )
            }

            var successCalled = false
            vm.save(onSuccess = { successCalled = true })
            advanceUntilIdle()

            successCalled shouldBe false
            vm.state.value.error shouldBe SettingsError.Domain(failure.text)
            (failure.text is LocalizableText.Keyed) shouldBe true
            vm.state.value.pendingTrustedCertAdds shouldHaveSize 1
        }
    }

    test("stageGlobalTrustedCert stages a new certificate with parsed metadata") {
        runTest(testDispatcher) {
            val trustStore: TrustStore = mockk()
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()
            coEvery { trustStore.inspect(any()) } returns TrustedCertificate(
                fingerprint = "sha256-new",
                subjectDN = "CN=New CA",
                notBefore = Instant.parse("2024-01-01T00:00:00Z"),
                notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                type = TrustedCertificateType.ANY,
            ).right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, credentialStore = credentialStore,
                ioDispatcher = testDispatcher, trustStore = trustStore,
            )
            vm.load()
            advanceUntilIdle()

            vm.stageGlobalTrustedCert(byteArrayOf(1), TrustedCertificateType.CA, "new.pem")
            advanceUntilIdle()

            val staged = vm.state.value.pendingTrustedCertAdds
            staged shouldHaveSize 1
            staged.first().subjectDN shouldBe "CN=New CA"
            staged.first().fingerprint shouldBe "sha256-new"
            staged.first().type shouldBe TrustedCertificateType.CA
            vm.state.value.trustedCertAddError.shouldBeNull()
        }
    }

    test("stageGlobalTrustedCert reports already-trusted when the fingerprint is in the baseline") {
        runTest(testDispatcher) {
            val trustStore: TrustStore = mockk()
            val existing = TrustedCertificate(
                fingerprint = "sha256-dup",
                subjectDN = "CN=Existing",
                notBefore = Instant.parse("2024-01-01T00:00:00Z"),
                notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                type = TrustedCertificateType.CA,
            )
            coEvery { configRepository.loadConfig() } returns baseConfig.right()
            coEvery { trustStore.list(TrustScope.Global) } returns listOf(existing).right()
            coEvery { trustStore.inspect(any()) } returns existing.copy(type = TrustedCertificateType.ANY).right()

            val vm = SettingsViewModel(
                getConfig, setGlobalConfig, credentialStore = credentialStore,
                ioDispatcher = testDispatcher, trustStore = trustStore,
            )
            vm.load()
            advanceUntilIdle()

            vm.stageGlobalTrustedCert(byteArrayOf(9), TrustedCertificateType.CA, "dup.pem")
            advanceUntilIdle()

            vm.state.value.pendingTrustedCertAdds.shouldBeEmpty()
            vm.state.value.trustedCertAddError.shouldNotBeNull()
        }
    }
})

