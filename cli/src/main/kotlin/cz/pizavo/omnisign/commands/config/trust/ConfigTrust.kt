package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * CLI command group for managing directly trusted certificates.
 *
 * Certificates are kept in the app-managed trust store, content-addressed by their SHA-256
 * fingerprint and partitioned into a global scope plus one scope per profile. They are wired into
 * DSS alongside any ETSI trusted lists without requiring a full ETSI TS 119612 trusted list XML.
 */
class ConfigTrust : CliktCommand(name = "trust") {
	init {
		subcommands(TrustCertAdder(), TrustCertLister(), TrustCertRemover())
	}
	
	override fun help(context: Context): String =
		"Manage directly trusted CA and TSA certificates"
	
	override fun run() = Unit
}

