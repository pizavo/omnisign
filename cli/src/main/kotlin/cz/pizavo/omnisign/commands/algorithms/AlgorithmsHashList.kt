package cz.pizavo.omnisign.commands.algorithms

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Lists all hash algorithms supported by OmniSign, together with a short description,
 * ETSI expiration date (where applicable), and any relevant usage caveats.
 *
 * The algorithm name printed here is the value accepted by `--hash-algorithm` on
 * `sign`, `timestamp`, `config set`, and `config profile create/edit`.
 */
class AlgorithmsHashList : CliktCommand(name = "list") {
	override fun help(context: Context): String =
		"List all supported hash algorithms with descriptions and notes"
	
	override fun run() {
		val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
		val entries = HashAlgorithm.entries
		echo("═══════════════════════════════════════════════════════════════")
		echo("  SUPPORTED HASH ALGORITHMS (${entries.size})")
		echo("═══════════════════════════════════════════════════════════════")
		entries.forEach { alg ->
			val expired = alg.expirationDate?.let { today > it } ?: false
			val expiredTag = if (expired) " ❌ EXPIRED" else ""
			echo("")
			echo("  ${alg.name}$expiredTag")
			echo("    ${alg.description}")
			alg.expirationDate?.let { date ->
				val label = if (expired) "Expired" else "Expires"
				echo("    $label: $date (ETSI TS 119 312)")
			}
			alg.notes?.let { echo("    ⚠️  $it") }
		}
		echo("")
		echo("═══════════════════════════════════════════════════════════════")
		echo("  Use --hash-algorithm <NAME> to select an algorithm.")
		echo("  Recommended: SHA256, SHA384, SHA512, SHA3_256, SHA3_512")
		echo("═══════════════════════════════════════════════════════════════")
	}
}
