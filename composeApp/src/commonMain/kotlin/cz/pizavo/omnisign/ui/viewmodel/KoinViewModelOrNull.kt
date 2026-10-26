package cz.pizavo.omnisign.ui.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.koin.core.Koin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.mp.KoinPlatform

/**
 * Returns the [VM] held by the current [LocalViewModelStoreOwner], building it with [create] from
 * the running Koin container the first time it is asked for.
 *
 * Unlike `viewModel {}`, this tolerates an incomplete container. It returns `null` when Koin has not
 * been started, or when [create] asks for a definition the container lacks, so a screen can leave
 * out a feature whose dependencies a platform does not provide. Any other failure propagates.
 *
 * The instance lives in the owner's store rather than in the composition, so it is cleared, and its
 * `viewModelScope` cancelled, when that owner is cleared.
 *
 * @param create Builds the ViewModel from the Koin container, with `get` for the dependencies it
 *   cannot do without and `getOrNull` for the optional ones.
 * @return The stored or newly created ViewModel, or `null` when it cannot be built.
 */
@Composable
inline fun <reified VM : ViewModel> koinViewModelOrNull(crossinline create: (Koin) -> VM): VM? {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    }
    return remember(owner) {
        val koin = KoinPlatform.getKoinOrNull() ?: return@remember null
        runCatching {
            ViewModelProvider.create(owner, viewModelFactory { initializer { create(koin) } })[VM::class]
        }.recover {
            if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it
        }.getOrNull()
    }
}
