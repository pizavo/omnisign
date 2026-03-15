package cz.pizavo.omnisign.commands.algorithms

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Top-level CLI command grouping informational subcommands about supported algorithms.
 */
class Algorithms : CliktCommand(name = "algorithms") {
	init {
		subcommands(AlgorithmsHash(), AlgorithmsEncryption())
	}
	
	override fun help(context: Context): String =
		"List supported cryptographic algorithms"
	
	override fun run() = Unit
}


