package cz.pizavo.omnisign

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import cz.pizavo.omnisign.commands.Sign
import cz.pizavo.omnisign.commands.Timestamp
import cz.pizavo.omnisign.commands.Validate
import cz.pizavo.omnisign.commands.certificatesCommand
import cz.pizavo.omnisign.commands.configCommand

/**
 * Main CLI entry point for Omnisign application.
 */
class Omnisign : CliktCommand(name = "omnisign") {
	override fun help(context: Context): String =
		"Digital signature verification, signing and re-timestamping tool"

	override fun run() = Unit
}

/**
 * Entry point with subcommands registered.
 */
fun omnisignCli() = Omnisign().subcommands(
	Sign(),
	Validate(),
	Timestamp(),
	certificatesCommand(),
	configCommand()
)
