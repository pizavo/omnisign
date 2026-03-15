package cz.pizavo.omnisign.commands.certificates

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Top-level CLI command for certificate discovery and management operations.
 */
class Certificates : CliktCommand(name = "certificates") {
	init {
		subcommands(CertificatesList())
	}
	
	override fun help(context: Context): String = "Discover and inspect available certificates"
	
	override fun run() = Unit
}


