package cz.pizavo.omnisign.commands.diagnose

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Top-level CLI command group for read-only diagnostic probes over the application's
 * subsystems.
 *
 * Diagnostic subcommands deliberately avoid mutating any state and produce structured
 * reports intended for ad-hoc troubleshooting and bug-report attachments.
 */
class Diagnose : CliktCommand(name = "diagnose") {
	init {
		subcommands(DiagnosePkcs11())
	}

	override fun help(context: Context): String =
		"Run read-only diagnostic probes over OmniSign subsystems"

	override fun run() = Unit
}
