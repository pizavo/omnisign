package cz.pizavo.omnisign.commands.config.pkcs11

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManagePkcs11LibrariesUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for removing a registered PKCS#11 middleware library by name.
 */
class Pkcs11LibraryRemover : CliktCommand(name = "remove"), KoinComponent {
    private val managePkcs11: ManagePkcs11LibrariesUseCase by inject()

    private val name by argument(help = "Name of the PKCS#11 library entry to remove")

    override fun help(context: Context): String =
        "Remove a registered PKCS#11 middleware library"

    override fun run(): Unit = runBlocking {
        managePkcs11.removeLibrary(name).fold(
            ifLeft = { error -> echo("❌ ${error.message}", err = true) },
            ifRight = { echo("✅ PKCS#11 library '$name' removed.") }
        )
    }
}

