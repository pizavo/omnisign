package cz.pizavo.omnisign

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import cz.pizavo.omnisign.cli.BuildConfig
import cz.pizavo.omnisign.commands.Renew
import cz.pizavo.omnisign.commands.Sign
import cz.pizavo.omnisign.commands.Timestamp
import cz.pizavo.omnisign.commands.Validate
import cz.pizavo.omnisign.commands.algorithms.Algorithms
import cz.pizavo.omnisign.commands.certificates.Certificates
import cz.pizavo.omnisign.commands.config.Config
import cz.pizavo.omnisign.commands.schedule.Schedule

/**
 * Main CLI entry point for Omnisign application.
 */
class Omnisign : CliktCommand(name = "omnisign") {
	init {
		versionOption(BuildConfig.VERSION, names = setOf("-v", "--version"))
		subcommands(Sign(), Validate(), Timestamp(), Renew(), Algorithms(), Certificates(), Config(), Schedule())
	}
	
	override fun help(context: Context): String =
		"Digital signature verification, signing and re-timestamping tool"
	
	override fun run() {
		if (currentContext.invokedSubcommand == null) echo(getFormattedHelp())
	}
}
