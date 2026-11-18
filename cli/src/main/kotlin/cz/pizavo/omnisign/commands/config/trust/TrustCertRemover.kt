package cz.pizavo.omnisign.commands.config.trust

import arrow.core.getOrElse
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import cz.pizavo.omnisign.cli.failWith
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for removing a directly trusted certificate by its fingerprint.
 *
 * A unique fingerprint prefix is accepted; the certificate is removed from the target scope and,
 * when no scope references it any more, from the store. A `--profile` that names no profile is
 * refused.
 */
class TrustCertRemover : CliktCommand(name = "remove"), KoinComponent {
	private val trustStore: TrustStore by inject()
	private val manageProfile: ManageProfileUseCase by inject()

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
		profile?.let { name -> manageProfile.get(name).getOrElse { failWith(it) } }
		val scope = TrustScope.of(profile)
		val scopeLabel = profile?.let { "profile '$it'" } ?: "global scope"
		val matches = trustStore.list(scope).fold(
			ifLeft = { failWith(it) },
			ifRight = { certs -> certs.filter { it.fingerprint.startsWith(fingerprint) } },
		)
		when (matches.size) {
			0 -> {
				echo("❌ No trusted certificate matching '$fingerprint' in $scopeLabel.", err = true)
				throw ProgramResult(1)
			}
			1 -> trustStore.remove(scope, matches.first().fingerprint).fold(
				ifLeft = { failWith(it) },
				ifRight = { echo("✅ Trusted certificate ${matches.first().fingerprint} removed from $scopeLabel.") },
			)

			else -> {
				echo("❌ '$fingerprint' is ambiguous (${matches.size} matches); use a longer prefix.", err = true)
				throw ProgramResult(1)
			}
		}
	}
}
