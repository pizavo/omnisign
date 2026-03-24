package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
})


