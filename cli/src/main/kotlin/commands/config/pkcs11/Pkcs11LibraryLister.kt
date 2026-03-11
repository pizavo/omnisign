package cz.pizavo.omnisign.commands.config.pkcs11

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.domain.usecase.ManagePkcs11LibrariesUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * CLI subcommand for listing all registered custom PKCS#11 middleware library entries.
 *
 * Each entry shows whether the library file currently exists on disk, so the user
 * can spot stale registrations at a glance.
 */
class Pkcs11LibraryLister : CliktCommand(name = "list"), KoinComponent {
    private val managePkcs11: ManagePkcs11LibrariesUseCase by inject()

    override fun help(context: Context): String =
        "List all registered custom PKCS#11 middleware libraries"

    override fun run(): Unit = runBlocking {
        managePkcs11.listLibraries().fold(
            ifLeft = { error -> echo("❌ ${error.message}", err = true) },
            ifRight = { libraries ->
                if (libraries.isEmpty()) {
                    echo("No custom PKCS#11 libraries registered.")
                    echo("Add one with: config pkcs11 add --name <label> --path <path>")
                } else {
                    echo("Registered PKCS#11 libraries:")
                    libraries.forEach { lib ->
                        val exists = File(lib.path).exists()
                        val status = if (exists) "✅" else "⚠️  (file not found)"
                        echo("  ● ${lib.name}  $status")
                        echo("    Path: ${lib.path}")
                    }
                }
            }
        )
    }
}

