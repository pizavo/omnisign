package cz.pizavo.omnisign

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import cz.pizavo.omnisign.commands.Validate

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
	Validate()
)
