package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing named configuration profiles.
 */
class ConfigProfile : CliktCommand(name = "profile") {
	init {
		subcommands(
			ProfileList(),
			ProfileShow(),
			ProfileCreate(),
			ProfileEdit(),
			ProfileUse(),
			ProfileRemove(),
			ProfileExport(),
			ProfileImport()
		)
	}
	
	override fun help(context: Context): String =
		"Manage named configuration profiles"
	
	override fun run() = Unit
}


