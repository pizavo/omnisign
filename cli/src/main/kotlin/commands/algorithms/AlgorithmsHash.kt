package cz.pizavo.omnisign.commands.algorithms

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Subcommand group for hash-algorithm information.
 */
class AlgorithmsHash : CliktCommand(name = "hash") {
	init {
		subcommands(AlgorithmsHashList())
	}
	
	override fun help(context: Context): String =
		"Information about supported hash algorithms"
	
	override fun run() = Unit
}

