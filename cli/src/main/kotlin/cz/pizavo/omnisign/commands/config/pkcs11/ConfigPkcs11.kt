package cz.pizavo.omnisign.commands.config.pkcs11

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing custom PKCS#11 middleware library registrations.
 *
 * Libraries registered here are merged into token discovery alongside the OS-native
 * autodiscovery results and the built-in fallback candidate list.
 */
class ConfigPkcs11 : CliktCommand(name = "pkcs11") {
    init {
        subcommands(Pkcs11LibraryAdder(), Pkcs11LibraryRemover(), Pkcs11LibraryLister())
    }

    override fun help(context: Context): String =
        "Manage custom PKCS#11 middleware library registrations"

    override fun run() = Unit
}

