package cz.pizavo.omnisign.ui.viewmodel

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Unit tests for [koinViewModelOrNull].
 *
 * Each case composes the helper in a bare composition with nothing rendered behind it, under a
 * store owner the test holds. That shows both what the helper returns and that the instance lives in
 * the owner's store rather than in the composition: a later composition gets the same instance, and
 * clearing the store cancels its scope, which is what lets the app's scoped store clear its
 * ViewModels on sign-out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KoinViewModelOrNullTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    beforeEach { Dispatchers.setMain(testDispatcher) }
    afterEach {
        stopKoin()
        Dispatchers.resetMain()
    }

    /**
     * Create a store owner with an empty store of its own.
     */
    fun storeOwner(): ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    /**
     * Compose [content] once under [owner], dispose the composition, and return what it produced.
     */
    fun <T> composeOnce(owner: ViewModelStoreOwner, content: @Composable () -> T): T {
        val produced = mutableListOf<T>()
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(
            object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            },
            recomposer,
        )
        try {
            composition.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { produced += content() }
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
        return produced.single()
    }

    /**
     * Compose a request for a [TrustedCertsViewModel], whose [GetConfigUseCase] is required.
     */
    fun requestTrustedCerts(owner: ViewModelStoreOwner): TrustedCertsViewModel? = composeOnce(owner) {
        koinViewModelOrNull { koin -> TrustedCertsViewModel(koin.get(), koin.getOrNull()) }
    }

    test("returns null while Koin has not been started") {
        requestTrustedCerts(storeOwner()).shouldBeNull()
    }

    test("returns null when a dependency the ViewModel requires has no definition") {
        startKoin { modules(module { }) }

        requestTrustedCerts(storeOwner()).shouldBeNull()
    }

    test("keeps the instance in the owner's store, so a later composition gets the same one") {
        startKoin { modules(module { single<GetConfigUseCase> { mockk(relaxed = true) } }) }
        val owner = storeOwner()

        val first = requestTrustedCerts(owner).shouldNotBeNull()

        requestTrustedCerts(owner) shouldBeSameInstanceAs first
    }

    test("clearing the owner's store cancels the ViewModel's scope") {
        startKoin { modules(module { single<GetConfigUseCase> { mockk(relaxed = true) } }) }
        val owner = storeOwner()
        val scopeJob = requestTrustedCerts(owner).shouldNotBeNull().viewModelScope.coroutineContext[Job].shouldNotBeNull()

        owner.viewModelStore.clear()

        scopeJob.isCancelled shouldBe true
    }

    test("a failure other than a missing definition propagates") {
        startKoin { modules(module { }) }

        shouldThrow<IllegalStateException> {
            composeOnce(storeOwner()) { koinViewModelOrNull<TrustedCertsViewModel> { error("construction failed") } }
        }
    }
})
