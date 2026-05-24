package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
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
import kotlin.time.Instant

/**
 * Unit tests for the read-only [TrustedCertsViewModel] overview panel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrustedCertsViewModelTest : FunSpec({

    val configRepository: ConfigRepository = mockk()
    val trustStore: TrustStore = mockk()
    val getConfig = GetConfigUseCase(configRepository)
    val testDispatcher = StandardTestDispatcher()

    fun cert(fingerprint: String, type: TrustedCertificateType = TrustedCertificateType.CA) =
        TrustedCertificate(
            fingerprint = "sha256-$fingerprint",
            subjectDN = "CN=$fingerprint",
            notBefore = Instant.parse("2024-01-01T00:00:00Z"),
            notAfter = Instant.parse("2030-01-01T00:00:00Z"),
            type = type,
        )

    beforeTest {
        clearMocks(configRepository, trustStore)
        Dispatchers.setMain(testDispatcher)
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("refresh loads global certificates when no active profile") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns AppConfig().right()
            coEvery { trustStore.list(TrustScope.Global) } returns
                    listOf(cert("global-ca"), cert("global-tsa", TrustedCertificateType.TSA)).right()

            val vm = TrustedCertsViewModel(getConfig, trustStore)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName.shouldBeNull()
            state.profileCertificates.shouldBeEmpty()
            state.globalCertificates shouldHaveSize 2
            state.globalCertificates[0].subjectDN shouldBe "CN=global-ca"
            state.available shouldBe true
            state.loading shouldBe false
            state.error.shouldBeNull()
        }
    }

    test("refresh loads both profile and global certificates") {
        runTest(testDispatcher) {
            val config = AppConfig(
                profiles = mapOf("dev" to ProfileConfig(name = "dev")),
                activeProfile = "dev",
            )
            coEvery { configRepository.loadConfig() } returns config.right()
            coEvery { trustStore.list(TrustScope.Global) } returns listOf(cert("global-ca")).right()
            coEvery { trustStore.list(TrustScope.Profile("dev")) } returns listOf(cert("profile-ca")).right()

            val vm = TrustedCertsViewModel(getConfig, trustStore)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName shouldBe "dev"
            state.profileCertificates shouldHaveSize 1
            state.profileCertificates.first().subjectDN shouldBe "CN=profile-ca"
            state.globalCertificates shouldHaveSize 1
            state.globalCertificates.first().subjectDN shouldBe "CN=global-ca"
        }
    }

    test("refresh yields empty lists when store has no certificates") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns AppConfig().right()
            coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()

            val vm = TrustedCertsViewModel(getConfig, trustStore)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileCertificates.shouldBeEmpty()
            state.globalCertificates.shouldBeEmpty()
            state.loading shouldBe false
        }
    }

    test("refresh surfaces error when store listing fails") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns AppConfig().right()
            coEvery { trustStore.list(TrustScope.Global) } returns
                    TrustStoreError.StorageFailed("disk error").left()

            val vm = TrustedCertsViewModel(getConfig, trustStore)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.error.shouldNotBeNull()
            state.loading shouldBe false
        }
    }

    test("refresh still lists global scope when config loading fails") {
        runTest(testDispatcher) {
            coEvery { configRepository.loadConfig() } returns ConfigurationError.LoadFailed("boom").left()
            coEvery { trustStore.list(TrustScope.Global) } returns listOf(cert("g")).right()

            val vm = TrustedCertsViewModel(getConfig, trustStore)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.profileName.shouldBeNull()
            state.globalCertificates shouldHaveSize 1
        }
    }

    test("refresh marks panel unavailable when no trust store is wired in") {
        runTest(testDispatcher) {
            val vm = TrustedCertsViewModel(getConfig, trustStore = null)
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            state.available shouldBe false
            state.globalCertificates.shouldBeEmpty()
            state.loading shouldBe false
        }
    }
})
