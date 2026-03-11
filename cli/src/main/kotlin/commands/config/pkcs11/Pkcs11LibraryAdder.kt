package cz.pizavo.omnisign.commands.config.pkcs11

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.usecase.ManagePkcs11LibrariesUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for registering a custom PKCS#11 middleware library.
 *
 * The registered library is merged into token discovery on every run, on top of the
 * OS-native autodiscovery results.  Use this when a vendor's middleware is installed
 * in a non-standard location or is not registered in the OS smart-card database.
 */
class Pkcs11LibraryAdder : CliktCommand(name = "add"), KoinComponent {
    private val managePkcs11: ManagePkcs11LibrariesUseCase by inject()

    private val name by option(
        "--name", "-n",
        help = "Unique label for this library (used in `pkcs11 remove`)"
    ).required()

    private val path by option(
        "--path", "-p",
        help = "Absolute path to the PKCS#11 shared library (.dll / .so / .dylib)"
    ).path(mustExist = true, canBeDir = false, mustBeReadable = true).required()

    override fun help(context: Context): String =
        "Register a custom PKCS#11 middleware library path"

    override fun run(): Unit = runBlocking {
        val library = CustomPkcs11Library(name = name, path = path.toString())
        managePkcs11.addLibrary(library).fold(
            ifLeft = { error ->
                echo("❌ ${error.message}", err = true)
                error.details?.let { echo("Details: $it", err = true) }
            },
            ifRight = {
                echo("✅ PKCS#11 library '$name' registered.")
                echo("   Path: $path")
            }
        )
    }
}

