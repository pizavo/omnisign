package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing custom trusted list sources and builder drafts.
 */
class ConfigTrustedList : CliktCommand(name = "tl") {
    override fun help(context: Context): String =
        "Manage custom trusted list sources and build new trusted lists"

    override fun run() = Unit
}

/**
 * Create the `config tl` command with all subcommands registered.
 */
fun configTrustedListCommand() = ConfigTrustedList().subcommands(
    TrustedListAdder(),
    TrustedListLister(),
    TrustedListRemover(),
    trustedListBuildCommand()
)

