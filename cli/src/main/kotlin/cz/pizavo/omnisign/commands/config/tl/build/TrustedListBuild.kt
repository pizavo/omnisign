package cz.pizavo.omnisign.commands.config.tl.build

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import cz.pizavo.omnisign.commands.config.tl.build.service.TrustedListBuildService
import cz.pizavo.omnisign.commands.config.tl.build.tsp.TrustedListBuildTsp

/**
 * CLI command group for the interactive TL builder workflow.
 *
 * The primary entry point is `create`, which guides the user through the entire
 * draft in a single interactive session.  The remaining subcommands allow
 * non-interactive editing of an existing draft after the fact.
 */
class TrustedListBuild : CliktCommand(name = "build") {
	init {
		subcommands(
			TrustedListBuildCreate(),
			TrustedListBuildList(),
			TrustedListBuildShow(),
			TrustedListBuildTsp(),
			TrustedListBuildService(),
			TrustedListBuildCompile(),
			TrustedListBuildDelete()
		)
	}
	
	override fun help(context: Context): String =
		"Build a custom trusted list interactively or edit an existing draft"
	
	override fun run() = Unit
}




