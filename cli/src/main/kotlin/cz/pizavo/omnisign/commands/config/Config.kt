package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import cz.pizavo.omnisign.commands.config.pkcs11.ConfigPkcs11
import cz.pizavo.omnisign.commands.config.profile.ConfigProfile
import cz.pizavo.omnisign.commands.config.tl.ConfigTrustedList
import cz.pizavo.omnisign.commands.config.trust.ConfigTrust

/**
 * Top-level CLI command for managing application configuration.
 * Groups subcommands for showing and modifying config settings and profiles.
 */
class Config : CliktCommand(name = "config") {
	init {
		subcommands(
			ConfigShow(),
			ConfigSet(),
			ConfigPath(),
			ConfigProfile(),
			ConfigTrustedList(),
			ConfigTrust(),
			ConfigPkcs11(),
			ConfigExport(),
			ConfigImport()
		)
	}
	
	override fun help(context: Context): String =
		"Manage application configuration"
	
	override fun run() = Unit
}


