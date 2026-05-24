package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.path.readBytes

/**
 * CLI subcommand for importing a directly trusted certificate into the app-managed trust store.
 *
 * The certificate file (PEM or DER) is imported and stored once under its SHA-256 fingerprint; the
 * original file is no longer needed afterward. The fingerprint is the identity, so no name is
 * required.
 */
class TrustCertAdder : CliktCommand(name = "add"), KoinComponent {
	private val trustStore: TrustStore by inject()

	private val cert by option(
		"--cert", "-c",
		help = "Path to the PEM or DER certificate file"
	).path(mustExist = true, canBeDir = false, mustBeReadable = true).required()

	private val type by option(
		"--type", "-t",
		help = "Certificate type: ANY (both CA and TSA), CA (Certificate Authority), or TSA (Time Stamping Authority)"
	).enum<TrustedCertificateType>().default(TrustedCertificateType.ANY)

	private val profile by option(
		"--profile", "-p",
		help = "Store in the given profile instead of the global scope"
	)

	override fun help(context: Context): String =
		"Trust a certificate directly (no TL XML required)"

	override fun run(): Unit = runBlocking {
		trustStore.add(TrustScope.of(profile), cert.readBytes(), type, source = cert.toString()).fold(
			ifLeft = { error ->
				echo("❌ ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
			},
			ifRight = { trusted ->
				val scope = profile?.let { "profile '$it'" } ?: "global scope"
				echo("✅ Trusted ${type.name} certificate added to $scope.")
				echo("Subject: ${trusted.subjectDN}")
				echo("Fingerprint: ${trusted.fingerprint}")
			}
		)
	}
}
