package cz.pizavo.omnisign.commands.algorithms

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm

/**
 * Lists all encryption (signing key) algorithms supported by OmniSign, together with
 * a short description and any relevant usage notes.
 *
 * The algorithm name printed here is the value accepted by `--encryption-algorithm` on
 * `sign`, `config set`, and `config profile create/edit`.
 */
class AlgorithmsEncryptionList : CliktCommand(name = "list") {
	override fun help(context: Context): String =
		"List all supported encryption (signing key) algorithms with descriptions and notes"
	
	override fun run() {
		val entries = EncryptionAlgorithm.entries
		echo("═══════════════════════════════════════════════════════════════")
		echo("  SUPPORTED ENCRYPTION ALGORITHMS (${entries.size})")
		echo("═══════════════════════════════════════════════════════════════")
		entries.forEach { alg ->
			echo("")
			echo("  ${alg.name}")
			echo("    ${alg.description}")
			alg.notes?.let { echo("    ⚠️  $it") }
			if (!alg.hasFixedHashAlgorithm) {
				echo("    Compatible hash algorithms: ${alg.compatibleHashAlgorithms.joinToString { it.name }}")
			}
		}
		echo("")
		echo("═══════════════════════════════════════════════════════════════")
		echo("  Use --encryption-algorithm <NAME> to select an algorithm.")
		echo("  When omitted, DSS infers the algorithm from the certificate key type.")
		echo("═══════════════════════════════════════════════════════════════")
	}
}

