package cz.pizavo.omnisign.commands.config.tl.build.service

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing trust services within a TSP in a draft.
 */
class TrustedListBuildService : CliktCommand(name = "service") {
	init {
		subcommands(TrustedListBuildServiceAdd(), TrustedListBuildServiceRemove())
	}
	
	override fun help(context: Context): String =
		"Manage trust services under a Trust Service Provider in a draft"
	
	override fun run() = Unit
}