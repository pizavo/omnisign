package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for removing a directly trusted certificate by its fingerprint.
 *
 * A unique fingerprint prefix is accepted; the certificate is removed from the target scope and,
 * when no scope references it any more, from the store.
 */
class TrustCertRemover : CliktCommand(name = "remove"), KoinComponent {
	private val trustStore: TrustStore by inject()

	private val fingerprint by argument(
		help = "Fingerprint of the trusted certificate to remove (a unique prefix is accepted)"
	)

	private val profile by option(
		"--profile", "-p",
		help = "Remove from the given profile instead of the global scope"
	)

	override fun help(context: Context): String =
		"Remove a directly trusted certificate"

	override fun run(): Unit = runBlocking {
		val scope = TrustScope.of(profile)
		val scopeLabel = profile?.let { "profile '$it'" } ?: "global scope"
		val matches = trustStore.list(scope).fold(
			ifLeft = { emptyList() },
			ifRight = { certs -> certs.filter { it.fingerprint.startsWith(fingerprint) } },
		)
		when (matches.size) {
			0 -> echo("❌ No trusted certificate matching '$fingerprint' in $scopeLabel.", err = true)
			1 -> trustStore.remove(scope, matches.first().fingerprint).fold(
				ifLeft = { echo("❌ ${it.message}", err = true) },
				ifRight = { echo("✅ Trusted certificate ${matches.first().fingerprint} removed from $scopeLabel.") },
			)

			else -> echo("❌ '$fingerprint' is ambiguous (${matches.size} matches); use a longer prefix.", err = true)
		}
	}
}
