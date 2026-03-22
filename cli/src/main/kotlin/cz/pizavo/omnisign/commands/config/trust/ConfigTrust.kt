package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing directly trusted certificates.
 *
 * These certificates are stored inline (Base64-encoded DER) in the application
 * config and wired into DSS without requiring a full ETSI TS 119612 trusted list.
 */
class ConfigTrust : CliktCommand(name = "trust") {
	init {
		subcommands(TrustCertAdder(), TrustCertLister(), TrustCertRemover())
	}
	
	override fun help(context: Context): String =
		"Manage directly trusted CA and TSA certificates"
	
	override fun run() = Unit
}

