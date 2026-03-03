package cz.pizavo.omnisign.commands.config.tl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import cz.pizavo.omnisign.commands.config.tl.build.TrustedListBuild

/**
 * CLI command group for managing custom trusted list sources and builder drafts.
 */
class ConfigTrustedList : CliktCommand(name = "tl") {
	init {
		subcommands(TrustedListAdder(), TrustedListLister(), TrustedListRemover(), TrustedListBuild())
	}
	
	override fun help(context: Context): String =
		"Manage custom trusted list sources and build new trusted lists"
	
	override fun run() = Unit
}


