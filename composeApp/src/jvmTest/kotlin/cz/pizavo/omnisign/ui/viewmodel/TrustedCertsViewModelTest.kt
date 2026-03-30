package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Unit tests for [TrustedCertsViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrustedCertsViewModelTest : FunSpec({

    val configRepository: ConfigRepository = mockk()
    val getConfig = GetConfigUseCase(configRepository)
    val testDispatcher = StandardTestDispatcher()

    fun cert(name: String, type: TrustedCertificateType = TrustedCertificateType.CA) =
        TrustedCertificateConfig(
            name = name,
            type = type,
            certificateBase64 = "AAAA",
            subjectDN = "CN=$name",
        )

    beforeTest {
        clearMocks(configRepository)
        Dispatchers.setMain(testDispatcher)
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("refresh loads global certificates when no active profile") {
        runTest(testDispatcher) {
            val config = AppConfig(
                global = GlobalConfig(
                    validation = ValidationConfig(
                        trustedCertificates = listOf(cert("global-ca"), cert("global-tsa", TrustedCertificateType.TSA)),
                    ),
                ),
            )
            coEvery { configRepository.loadConfig() } returns config.right()

            val vm = TrustedCertsViewModel(getConfig)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName.shouldBeNull()
            state.profileCertificates.shouldBeEmpty()
            state.globalCertificates shouldHaveSize 2
            state.globalCertificates[0].name shouldBe "global-ca"
            state.globalCertificates[1].name shouldBe "global-tsa"
            state.loading shouldBe false
            state.error.shouldBeNull()
        }
    }

    test("refresh loads both profile and global certificates") {
        runTest(testDispatcher) {
            val config = AppConfig(
                global = GlobalConfig(
                    validation = ValidationConfig(
                        trustedCertificates = listOf(cert("global-ca")),
                    ),
                ),
                profiles = mapOf(
                    "dev" to ProfileConfig(
                        name = "dev",
                        validation = ValidationConfig(
                            trustedCertificates = listOf(cert("profile-ca")),
                        ),
                    ),
                ),
                activeProfile = "dev",
            )
            coEvery { configRepository.loadConfig() } returns config.right()

            val vm = TrustedCertsViewModel(getConfig)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName shouldBe "dev"
            state.profileCertificates shouldHaveSize 1
            state.profileCertificates.first().name shouldBe "profile-ca"
            state.globalCertificates shouldHaveSize 1
            state.globalCertificates.first().name shouldBe "global-ca"
        }
    }

    test("refresh yields empty lists when no certificates configured") {
        runTest(testDispatcher) {
            val config = AppConfig()
            coEvery { configRepository.loadConfig() } returns config.right()

            val vm = TrustedCertsViewModel(getConfig)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName.shouldBeNull()
            state.profileCertificates.shouldBeEmpty()
            state.globalCertificates.shouldBeEmpty()
            state.loading shouldBe false
        }
    }

    test("refresh shows profile name with empty cert list when profile has no validation") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("empty" to ProfileConfig(name = "empty")),
                activeProfile = "empty",
            )
            coEvery { configRepository.loadConfig() } returns config.right()

            val vm = TrustedCertsViewModel(getConfig)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName shouldBe "empty"
            state.profileCertificates.shouldBeEmpty()
        }
    }

    test("refresh surfaces error when config loading fails") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns ConfigurationError.LoadFailed(
                "disk error"
            ).left()

            val vm = TrustedCertsViewModel(getConfig)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.error.shouldNotBeNull()
            state.loading shouldBe false
        }
    }

    test("refresh ignores profile certificates when active profile does not exist in map") {
        runTest(testDispatcher) {
            val config = AppConfig(
                global = GlobalConfig(
                    validation = ValidationConfig(
                        trustedCertificates = listOf(cert("g")),
                    ),
                ),
                activeProfile = "ghost",
            )
            coEvery { configRepository.loadConfig() } returns config.right()

            val vm = TrustedCertsViewModel(getConfig)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName shouldBe "ghost"
            state.profileCertificates.shouldBeEmpty()
            state.globalCertificates shouldHaveSize 1
        }
    }
})

