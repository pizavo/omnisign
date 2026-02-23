package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Top-level CLI command for managing application configuration.
 * Groups subcommands for showing and modifying config settings and profiles.
 */
class Config : CliktCommand(name = "config") {
    override fun help(context: Context): String =
        "Manage application configuration"

    override fun run() = Unit
}

/**
 * Create the config command with all subcommands registered.
 */
fun configCommand() = Config().subcommands(
    ConfigShow(),
    ConfigSet(),
    configProfileCommand(),
    configTrustedListCommand()
)


