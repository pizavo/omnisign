package cz.pizavo.omnisign.ui.viewmodel

import cz.pizavo.omnisign.api.model.responses.CapabilitiesResponse
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * Unit tests for [CapabilitiesViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    beforeEach { Dispatchers.setMain(testDispatcher) }
    afterEach { Dispatchers.resetMain() }

    fun response(operations: List<String>) = CapabilitiesResponse(
        allowedOperations = operations,
        profiles = emptyList(),
        maxFileSize = 1024,
        authEnabled = false,
    )

    test("null repository leaves every operation permitted (desktop)") {
        val vm = CapabilitiesViewModel(capabilitiesRepository = null, ioDispatcher = testDispatcher)
        val caps = vm.capabilities.value
        caps.canValidate shouldBe true
        caps.canSign shouldBe true
        caps.canTimestamp shouldBe true
    }

    test("maps allowedOperations to the matching flags") {
        runTest(testDispatcher) {
            val repo = mockk<CapabilitiesRepository>()
            coEvery { repo.get() } returns response(listOf("VALIDATE", "SIGN"))

            val vm = CapabilitiesViewModel(repo, testDispatcher)
            advanceUntilIdle()

            val caps = vm.capabilities.value
            caps.canValidate shouldBe true
            caps.canSign shouldBe true
            caps.canTimestamp shouldBe false
        }
    }

    test("empty allowedOperations disables every operation") {
        runTest(testDispatcher) {
            val repo = mockk<CapabilitiesRepository>()
            coEvery { repo.get() } returns response(emptyList())

            val vm = CapabilitiesViewModel(repo, testDispatcher)
            advanceUntilIdle()

            val caps = vm.capabilities.value
            caps.canValidate shouldBe false
            caps.canSign shouldBe false
            caps.canTimestamp shouldBe false
        }
    }

    test("operation names are matched case-insensitively") {
        runTest(testDispatcher) {
            val repo = mockk<CapabilitiesRepository>()
            coEvery { repo.get() } returns response(listOf("timestamp"))

            val vm = CapabilitiesViewModel(repo, testDispatcher)
            advanceUntilIdle()

            val caps = vm.capabilities.value
            caps.canTimestamp shouldBe true
            caps.canSign shouldBe false
        }
    }

    test("fetch failure keeps the optimistic all-permitted default") {
        runTest(testDispatcher) {
            val repo = mockk<CapabilitiesRepository>()
            coEvery { repo.get() } throws RuntimeException("network down")

            val vm = CapabilitiesViewModel(repo, testDispatcher)
            advanceUntilIdle()

            val caps = vm.capabilities.value
            caps.canValidate shouldBe true
            caps.canSign shouldBe true
            caps.canTimestamp shouldBe true
        }
    }
})
