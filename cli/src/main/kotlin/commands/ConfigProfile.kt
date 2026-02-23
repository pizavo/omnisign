package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing named configuration profiles.
 */
class ConfigProfile : CliktCommand(name = "profile") {
    override fun help(context: Context): String =
        "Manage named configuration profiles"

    override fun run() = Unit
}

/**
 * Create the profile command with all subcommands registered.
 */
fun configProfileCommand() = ConfigProfile().subcommands(
    ProfileList(),
    ProfileCreate(),
    ProfileEdit(),
    ProfileUse(),
    ProfileRemove()
)

