package cz.pizavo.omnisign.commands.algorithms

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Subcommand group for encryption-algorithm information.
 */
class AlgorithmsEncryption : CliktCommand(name = "encryption") {
	init {
		subcommands(AlgorithmsEncryptionList())
	}
	
	override fun help(context: Context): String =
		"Information about supported encryption (signing key) algorithms"
	
	override fun run() = Unit
}

