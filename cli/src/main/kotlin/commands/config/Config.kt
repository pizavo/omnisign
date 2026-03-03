package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import cz.pizavo.omnisign.commands.config.profile.ConfigProfile
import cz.pizavo.omnisign.commands.config.tl.ConfigTrustedList

/**
 * Top-level CLI command for managing application configuration.
 * Groups subcommands for showing and modifying config settings and profiles.
 */
class Config : CliktCommand(name = "config") {
	init {
		subcommands(ConfigShow(), ConfigSet(), ConfigProfile(), ConfigTrustedList(), ConfigExport(), ConfigImport())
	}
	
	override fun help(context: Context): String =
		"Manage application configuration"
	
	override fun run() = Unit
}


