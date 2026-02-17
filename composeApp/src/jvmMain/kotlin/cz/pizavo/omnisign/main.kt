package cz.pizavo.omnisign

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import org.koin.core.context.startKoin

fun main() = application {
    // Initialize Koin with all modules
    startKoin {
        modules(
            appModule,           // Common module from shared
            jvmRepositoryModule, // JVM repositories from shared
        )
    }
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "omnisign",
    ) {
        App()
    }
}

