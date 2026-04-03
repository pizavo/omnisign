package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.ui.model.ProfilePanelMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Unit tests for [ProfileViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest : FunSpec({

    val configRepository: ConfigRepository = mockk()
    val manageProfile = ManageProfileUseCase(configRepository)
    val getConfig = GetConfigUseCase(configRepository)
    val testDispatcher = StandardTestDispatcher()

    fun profile(name: String, description: String? = null) = ProfileConfig(
        name = name,
        description = description,
    )

    beforeTest {
        clearMocks(configRepository)
        Dispatchers.setMain(testDispatcher)
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("initial refresh loads profiles and active profile") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf(
                    "dev" to profile("dev", "Development"),
                    "prod" to profile("prod"),
                ),
                activeProfile = "dev",
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            val state = vm.state.value
            state.profiles shouldHaveSize 2
            state.activeProfile shouldBe "dev"
            state.loading shouldBe false
            state.error.shouldBeNull()
        }
    }

    test("refresh reports error when list fails") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns
                    ConfigurationError.LoadFailed("disk error").left()
            coEvery { configRepository.getCurrentConfig() } returns AppConfig()

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            val state = vm.state.value
            state.profiles.shouldBeEmpty()
            state.loading shouldBe false
            state.error.shouldBeNull()
        }
    }

    test("toggleActive activates an inactive profile") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
                activeProfile = null,
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.state.value.activeProfile.shouldBeNull()

            vm.toggleActive("dev")
            advanceUntilIdle()

            vm.state.value.activeProfile shouldBe "dev"
            saved.captured.activeProfile shouldBe "dev"
        }
    }

    test("toggleActive deactivates an already-active profile") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
                activeProfile = "dev",
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } returns Unit.right()

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.state.value.activeProfile shouldBe "dev"

            vm.toggleActive("dev")
            advanceUntilIdle()

            vm.state.value.activeProfile.shouldBeNull()
            saved.captured.activeProfile.shouldBeNull()
        }
    }

    test("delete removes a profile and refreshes") {
        runTest(testDispatcher) {
            var currentConfig = AppConfig(
                profiles = mapOf(
                    "dev" to profile("dev"),
                    "prod" to profile("prod"),
                ),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.state.value.profiles shouldHaveSize 2

            vm.delete("dev")
            advanceUntilIdle()

            saved.captured.profiles.keys shouldBe setOf("prod")
            vm.state.value.profiles shouldHaveSize 1
            vm.state.value.profiles.first().name shouldBe "prod"
        }
    }

    test("delete sets error when profile does not exist") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.delete("ghost")
            advanceUntilIdle()

            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("empty profile list shows no profiles") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            val state = vm.state.value
            state.profiles.shouldBeEmpty()
            state.activeProfile.shouldBeNull()
            state.loading shouldBe false
        }
    }

    test("startCreate sets creatingNew to true") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.state.value.creatingNew shouldBe false

            vm.startCreate()

            vm.state.value.creatingNew shouldBe true
            vm.state.value.error.shouldBeNull()
        }
    }

    test("cancelCreate sets creatingNew to false") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startCreate()
            vm.state.value.creatingNew shouldBe true

            vm.cancelCreate()

            vm.state.value.creatingNew shouldBe false
            vm.state.value.error.shouldBeNull()
        }
    }

    test("confirmCreate with blank name sets error and keeps creatingNew true") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startCreate()
            vm.confirmCreate("   ")
            advanceUntilIdle()

            vm.state.value.creatingNew shouldBe true
            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("confirmCreate with valid name creates profile and hides creation row") {
        runTest(testDispatcher) {
            var currentConfig = AppConfig()
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startCreate()
            vm.state.value.creatingNew shouldBe true

            vm.confirmCreate("new-profile")
            advanceUntilIdle()

            vm.state.value.creatingNew shouldBe false
            vm.state.value.profiles shouldHaveSize 1
            vm.state.value.profiles.first().name shouldBe "new-profile"
            vm.state.value.error.shouldBeNull()
        }
    }

    test("confirmCreate trims whitespace from name") {
        runTest(testDispatcher) {
            var currentConfig = AppConfig()
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startCreate()
            vm.confirmCreate("  trimmed  ")
            advanceUntilIdle()

            vm.state.value.profiles.first().name shouldBe "trimmed"
        }
    }

    test("startEdit loads profile and switches to Editing mode") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("dev" to profile("dev", "Development")),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            val state = vm.state.value
            state.mode shouldBe ProfilePanelMode.Editing("dev")
            val editState = state.editState.shouldNotBeNull()
            editState.profileName shouldBe "dev"
            editState.description shouldBe "Development"
            state.error.shouldBeNull()
        }
    }

    test("startEdit sets error for unknown profile") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("missing")
            advanceUntilIdle()

            vm.state.value.mode shouldBe ProfilePanelMode.Listing
            vm.state.value.error.shouldNotBeNull()
        }
    }

    test("cancelEdit returns to Listing mode") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()
            vm.state.value.mode shouldBe ProfilePanelMode.Editing("dev")

            vm.cancelEdit()

            vm.state.value.mode shouldBe ProfilePanelMode.Listing
            vm.state.value.editState.shouldBeNull()
            vm.state.value.error.shouldBeNull()
        }
    }

    test("updateEditState applies transform to editState") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(description = "Updated desc") }

            vm.state.value.editState.shouldNotBeNull()
            vm.state.value.editState!!.description shouldBe "Updated desc"
        }
    }

    test("updateEditState is no-op when not in edit mode") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.updateEditState { it.copy(description = "should not apply") }

            vm.state.value.editState.shouldBeNull()
        }
    }

    test("saveEdit persists changes and returns to Listing") {
        runTest(testDispatcher) {
            var currentConfig = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(description = "New description", hashAlgorithm = HashAlgorithm.SHA512) }
            vm.saveEdit()
            advanceUntilIdle()

            vm.state.value.mode shouldBe ProfilePanelMode.Listing
            vm.state.value.editState.shouldBeNull()
            val savedProfile = saved.captured.profiles["dev"]
            savedProfile.shouldNotBeNull()
            savedProfile.description shouldBe "New description"
            savedProfile.hashAlgorithm shouldBe HashAlgorithm.SHA512
        }
    }

    test("saveEdit with validation error keeps Editing mode") {
        runTest(testDispatcher) {
            val devProfile = ProfileConfig(
                name = "dev",
                hashAlgorithm = HashAlgorithm.SHA256,
            )
            var currentConfig = AppConfig(
                profiles = mapOf("dev" to devProfile),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            coEvery { configRepository.saveConfig(any()) } returns Unit.right()

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState {
                it.copy(
                    hashAlgorithm = HashAlgorithm.SHA256,
                    disabledHashAlgorithms = setOf(HashAlgorithm.SHA256),
                )
            }
            vm.saveEdit()
            advanceUntilIdle()

            vm.state.value.mode shouldBe ProfilePanelMode.Editing("dev")
            vm.state.value.editState.shouldNotBeNull()
            vm.state.value.editState!!.error.shouldNotBeNull()
            vm.state.value.editState!!.saving shouldBe false
        }
    }

    test("startEdit sets hasStoredPassword when credential exists") {
        runTest(testDispatcher) {
            val tsaProfile = ProfileConfig(
                name = "tsa-profile",
                timestampServer = TimestampServerConfig(
                    url = "https://tsa.example.com",
                    username = "user1",
                    credentialKey = "user1",
                ),
            )
            val config = AppConfig(profiles = mapOf("tsa-profile" to tsaProfile))
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val credStore: CredentialStore = mockk()
            io.mockk.every { credStore.getPassword("omnisign-tsa", "user1") } returns "secret"

            val vm = ProfileViewModel(manageProfile, getConfig, credStore)
            advanceUntilIdle()

            vm.startEdit("tsa-profile")
            advanceUntilIdle()

            val editState = vm.state.value.editState.shouldNotBeNull()
            editState.hasStoredPassword shouldBe true
            editState.timestampPassword shouldBe ""
        }
    }

    test("saveEdit stores TSA password in credential store") {
        runTest(testDispatcher) {
            var currentConfig = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val credStore: CredentialStore = mockk(relaxed = true)
            io.mockk.every { credStore.getPassword(any(), any()) } returns null

            val vm = ProfileViewModel(manageProfile, getConfig, credStore)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState {
                it.copy(
                    timestampEnabled = true,
                    timestampUrl = "https://tsa.example.com",
                    timestampUsername = "admin",
                    timestampPassword = "s3cret",
                )
            }
            vm.saveEdit()
            advanceUntilIdle()

            io.mockk.verify { credStore.setPassword("omnisign-tsa", "admin", "s3cret") }
            vm.state.value.mode shouldBe ProfilePanelMode.Listing
        }
    }

    test("refresh propagates global disabled algorithms to state") {
        runTest(testDispatcher) {
            val config = AppConfig(
                global = GlobalConfig(
                    disabledHashAlgorithms = setOf(HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL),
                    disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
                ),
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            val state = vm.state.value
            state.globalDisabledHashAlgorithms.shouldContainExactlyInAnyOrder(
                HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL,
            )
            state.globalDisabledEncryptionAlgorithms.shouldContainExactlyInAnyOrder(
                EncryptionAlgorithm.DSA,
            )
        }
    }

    test("refresh with no disabled algorithms yields empty sets") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            val state = vm.state.value
            state.globalDisabledHashAlgorithms.shouldBeEmpty()
            state.globalDisabledEncryptionAlgorithms.shouldBeEmpty()
        }
    }

    test("startEdit loads profile-scoped trusted certificates into editState") {
        runTest(testDispatcher) {
            val cert = TrustedCertificateConfig(
                name = "profile-ca",
                type = TrustedCertificateType.CA,
                certificateBase64 = "AAAA",
                subjectDN = "CN=Profile CA",
            )
            val config = AppConfig(
                profiles = mapOf(
                    "dev" to ProfileConfig(
                        name = "dev",
                        validation = ValidationConfig(trustedCertificates = listOf(cert)),
                    ),
                ),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            val editState = vm.state.value.editState.shouldNotBeNull()
            editState.trustedCertificates shouldHaveSize 1
            editState.trustedCertificates.first().name shouldBe "profile-ca"
        }
    }

    test("saveEdit persists profile-scoped trusted certificates") {
        runTest(testDispatcher) {
            var currentConfig = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val cert = TrustedCertificateConfig(
                name = "new-ca",
                type = TrustedCertificateType.ANY,
                certificateBase64 = "CCCC",
                subjectDN = "CN=New CA",
            )

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(trustedCertificates = listOf(cert)) }
            vm.saveEdit()
            advanceUntilIdle()

            vm.state.value.mode shouldBe ProfilePanelMode.Listing
            val savedProfile = saved.captured.profiles["dev"].shouldNotBeNull()
            savedProfile.validation.shouldNotBeNull()
            savedProfile.validation!!.trustedCertificates shouldHaveSize 1
            savedProfile.validation!!.trustedCertificates.first().name shouldBe "new-ca"
        }
    }

    test("saveEdit clears validation when trusted certificates are removed") {
        runTest(testDispatcher) {
            val cert = TrustedCertificateConfig(
                name = "old-ca",
                type = TrustedCertificateType.CA,
                certificateBase64 = "DDDD",
                subjectDN = "CN=Old CA",
            )
            var currentConfig = AppConfig(
                profiles = mapOf(
                    "dev" to ProfileConfig(
                        name = "dev",
                        validation = ValidationConfig(trustedCertificates = listOf(cert)),
                    ),
                ),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(trustedCertificates = emptyList()) }
            vm.saveEdit()
            advanceUntilIdle()

            val savedProfile = saved.captured.profiles["dev"].shouldNotBeNull()
            savedProfile.validation.shouldBeNull()
        }
    }

    test("hasEditChanges is false right after entering edit mode") {
        runTest(testDispatcher) {
            val config = AppConfig(profiles = mapOf("dev" to profile("dev")))
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.hasEditChanges.value shouldBe false
        }
    }

    test("hasEditChanges becomes true when a field is modified") {
        runTest(testDispatcher) {
            val config = AppConfig(profiles = mapOf("dev" to profile("dev")))
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(description = "changed") }
            advanceUntilIdle()

            vm.hasEditChanges.value shouldBe true
        }
    }

    test("hasEditChanges reverts to false when field is restored to original value") {
        runTest(testDispatcher) {
            val config = AppConfig(profiles = mapOf("dev" to profile("dev")))
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(description = "changed") }
            advanceUntilIdle()
            vm.hasEditChanges.value shouldBe true

            vm.updateEditState { it.copy(description = "") }
            advanceUntilIdle()
            vm.hasEditChanges.value shouldBe false
        }
    }

    test("hasEditChanges resets to false after cancelEdit") {
        runTest(testDispatcher) {
            val config = AppConfig(profiles = mapOf("dev" to profile("dev")))
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(description = "changed") }
            advanceUntilIdle()
            vm.hasEditChanges.value shouldBe true

            vm.cancelEdit()
            advanceUntilIdle()
            vm.hasEditChanges.value shouldBe false
        }
    }

    test("startEdit loads profile-scoped custom trusted lists into editState") {
        runTest(testDispatcher) {
            val tl = CustomTrustedListConfig(
                name = "profile-tl",
                source = "https://example.com/profile-tl.xml",
                signingCertPath = "/path/to/cert.pem",
            )
            val config = AppConfig(
                profiles = mapOf(
                    "dev" to ProfileConfig(
                        name = "dev",
                        validation = ValidationConfig(customTrustedLists = listOf(tl)),
                    ),
                ),
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { configRepository.getCurrentConfig() } returns config

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            val editState = vm.state.value.editState.shouldNotBeNull()
            editState.customTrustedLists shouldHaveSize 1
            editState.customTrustedLists.first().name shouldBe "profile-tl"
        }
    }

    test("saveEdit persists profile-scoped custom trusted lists") {
        runTest(testDispatcher) {
            var currentConfig = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val tl = CustomTrustedListConfig(
                name = "new-tl",
                source = "https://example.com/new-tl.xml",
            )

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(customTrustedLists = listOf(tl)) }
            vm.saveEdit()
            advanceUntilIdle()

            vm.state.value.mode shouldBe ProfilePanelMode.Listing
            val savedProfile = saved.captured.profiles["dev"].shouldNotBeNull()
            savedProfile.validation.shouldNotBeNull()
            savedProfile.validation!!.customTrustedLists shouldHaveSize 1
            savedProfile.validation!!.customTrustedLists.first().name shouldBe "new-tl"
        }
    }

    test("saveEdit clears validation when both trusted certificates and trusted lists are removed") {
        runTest(testDispatcher) {
            val tl = CustomTrustedListConfig(
                name = "old-tl",
                source = "https://example.com/old-tl.xml",
            )
            var currentConfig = AppConfig(
                profiles = mapOf(
                    "dev" to ProfileConfig(
                        name = "dev",
                        validation = ValidationConfig(customTrustedLists = listOf(tl)),
                    ),
                ),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState { it.copy(customTrustedLists = emptyList()) }
            vm.saveEdit()
            advanceUntilIdle()

            val savedProfile = saved.captured.profiles["dev"].shouldNotBeNull()
            savedProfile.validation.shouldBeNull()
        }
    }

    test("saveEdit keeps validation when only trusted lists remain") {
        runTest(testDispatcher) {
            val tl = CustomTrustedListConfig(
                name = "keep-tl",
                source = "https://example.com/keep-tl.xml",
            )
            var currentConfig = AppConfig(
                profiles = mapOf("dev" to profile("dev")),
            )
            coEvery { configRepository.loadConfig() } answers { currentConfig.right() }
            coEvery { configRepository.getCurrentConfig() } answers { currentConfig }
            val saved = slot<AppConfig>()
            coEvery { configRepository.saveConfig(capture(saved)) } answers {
                currentConfig = saved.captured
                Unit.right()
            }

            val vm = ProfileViewModel(manageProfile, getConfig)
            advanceUntilIdle()

            vm.startEdit("dev")
            advanceUntilIdle()

            vm.updateEditState {
                it.copy(
                    trustedCertificates = emptyList(),
                    customTrustedLists = listOf(tl),
                )
            }
            vm.saveEdit()
            advanceUntilIdle()

            val savedProfile = saved.captured.profiles["dev"].shouldNotBeNull()
            savedProfile.validation.shouldNotBeNull()
            savedProfile.validation!!.customTrustedLists shouldHaveSize 1
            savedProfile.validation!!.trustedCertificates.shouldBeEmpty()
        }
    }
})

