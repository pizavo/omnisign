package cz.pizavo.omnisign.ui.viewmodel

import cz.pizavo.omnisign.api.model.responses.CreditsResponse
import cz.pizavo.omnisign.domain.repository.ServerCreditsRepository
import cz.pizavo.omnisign.legal.ThirdPartyComponent
import cz.pizavo.omnisign.ui.model.ServerCreditsState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

/**
 * Unit tests for [CreditsViewModel].
 *
 * The states that matter most are the failure ones: a deployment can be unreachable or predate the
 * credits endpoint entirely, and neither may be allowed to stop the bundled components from being
 * credited, so both must resolve to [ServerCreditsState.Unavailable] rather than propagate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreditsViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    beforeEach { Dispatchers.setMain(testDispatcher) }
    afterEach { Dispatchers.resetMain() }

    fun component(name: String) = ThirdPartyComponent(
        name = name,
        licenseId = "LGPL-2.1-or-later",
        licenseName = "GNU Lesser General Public License v2.1 or later",
        licenseText = "LGPL-2.1.txt",
        copyright = "Copyright 2015 European Commission",
        homepage = "https://github.com/esig/dss",
        surfaces = listOf("server"),
        artifacts = 40,
    )

    test("null repository leaves the section absent (desktop)") {
        val vm = CreditsViewModel(serverCreditsRepository = null, ioDispatcher = testDispatcher)

        vm.serverCredits.value shouldBe ServerCreditsState.NotApplicable
    }

    test("starts in Loading while a server is being asked") {
        val repo = mockk<ServerCreditsRepository>()

        val vm = CreditsViewModel(repo, testDispatcher)

        vm.serverCredits.value shouldBe ServerCreditsState.Loading
    }

    test("publishes the server's components, licence and source") {
        runTest(testDispatcher) {
            val repo = mockk<ServerCreditsRepository>()
            coEvery { repo.get() } returns CreditsResponse(
                components = listOf(component("EU DSS (Digital Signature Services)")),
            )

            val vm = CreditsViewModel(repo, testDispatcher)
            advanceUntilIdle()

            val state = vm.serverCredits.value.shouldBeInstanceOf<ServerCreditsState.Loaded>()
            state.components.single().name shouldBe "EU DSS (Digital Signature Services)"
            state.license shouldBe "AGPL-3.0-or-later"
            state.source shouldBe "https://github.com/pizavo/omnisign"
        }
    }

    test("an unreachable or outdated server degrades to Unavailable") {
        runTest(testDispatcher) {
            val repo = mockk<ServerCreditsRepository>()
            coEvery { repo.get() } throws IllegalStateException("404 Not Found")

            val vm = CreditsViewModel(repo, testDispatcher)
            advanceUntilIdle()

            vm.serverCredits.value shouldBe ServerCreditsState.Unavailable
        }
    }

    test("a server answering with no components is treated as unavailable, not as an empty list") {
        runTest(testDispatcher) {
            val repo = mockk<ServerCreditsRepository>()
            coEvery { repo.get() } returns CreditsResponse(components = emptyList())

            val vm = CreditsViewModel(repo, testDispatcher)
            advanceUntilIdle()

            vm.serverCredits.value shouldBe ServerCreditsState.Unavailable
        }
    }
})
