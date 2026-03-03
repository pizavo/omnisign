package cz.pizavo.omnisign.commands.config.tl.build.tsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing Trust Service Providers within a draft.
 */
class TrustedListBuildTsp : CliktCommand(name = "tsp") {
	init {
		subcommands(TrustedListBuildTspAdd(), TrustedListBuildTspRemove())
	}
	
	override fun help(context: Context): String =
		"Manage Trust Service Providers in a draft"
	
	override fun run() = Unit
}