package cz.pizavo.omnisign.commands.certificates

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import cz.pizavo.omnisign.cli.OutputConfig
import cz.pizavo.omnisign.cli.json.JsonCertificateList
import cz.pizavo.omnisign.cli.json.toJsonCertificateList
import cz.pizavo.omnisign.cli.json.toJsonError
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.domain.model.value.formatDate
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand that lists all certificates available from configured token sources
 * (PKCS#12 files, PKCS#11 hardware tokens, Windows keystore, macOS Keychain, etc.).
 *
 * The alias printed here is what should be supplied to `sign --certificate <alias>`.
 * Per-token warnings are printed to stderr when a token could not be accessed, so the
 * user understands why expected certificates may be absent.
 */
class CertificatesList : CliktCommand(name = "list"), KoinComponent {
	private val listCertificates: ListCertificatesUseCase by inject()
	private val output by requireObject<OutputConfig>()

	override fun help(context: Context): String =
		"List all certificates available for signing"

	override fun run(): Unit = runBlocking {
		listCertificates().fold(
			ifLeft = { error ->
				if (output.json) {
					echo(Json.encodeToString(JsonCertificateList(
						success = false,
						error = error.toJsonError()
					)))
				} else {
					echo("❌ Failed to list certificates: ${error.message}", err = true)
					error.details?.let { echo("Details: $it", err = true) }
					error.cause?.let { echo("Cause: ${it.message}", err = true) }
				}
				throw ProgramResult(1)
			},
			ifRight = { result ->
				if (output.json) {
					echo(Json.encodeToString(result.toJsonCertificateList()))
				} else {
					printTokenWarnings(result)
					if (result.certificates.isEmpty()) {
						printEmptyMessage(result)
					} else {
						printCertificatesTable(result.certificates)
					}
				}
			}
		)
	}

	/**
	 * Print per-token access warnings to stderr so they do not pollute stdout pipelines.
	 */
	private fun printTokenWarnings(result: CertificateDiscoveryResult) {
		result.tokenWarnings.forEach { warning ->
			echo("⚠️  Could not read token '${warning.tokenName}': ${warning.message}", err = true)
			warning.details?.let { echo("   └─ $it", err = true) }
		}
	}

	/**
	 * Print a contextual message when no signing-capable certificates were found.
	 */
	private fun printEmptyMessage(result: CertificateDiscoveryResult) {
		if (result.tokenWarnings.isNotEmpty()) {
			echo(
				"No signing-capable certificates found. " +
				"${result.tokenWarnings.size} token(s) could not be accessed " +
				"(see warnings above). " +
				"Ensure the token is connected and try again, or configure a PKCS#12 file via 'config set'."
			)
		} else {
			echo("No certificates found. Configure a PKCS#12 file or hardware token via 'config set'.")
		}
	}

	/**
	 * Print a formatted table of available certificates to stdout.
	 */
	private fun printCertificatesTable(certificates: List<AvailableCertificateInfo>) {
		val aliasWidth = maxOf(certificates.maxOf { it.alias.length }, 5)
		val subjectWidth = maxOf(certificates.maxOf { it.subjectDN.length }, 7)
		val tokenWidth = maxOf(certificates.maxOf { it.tokenType.length }, 5)

		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 16))
		echo("  AVAILABLE CERTIFICATES (${certificates.size})")
		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 16))

		certificates.forEachIndexed { index, cert ->
			echo("")
			echo("  [${index + 1}] Alias      : ${cert.alias}")
			echo("      Subject    : ${cert.subjectDN}")
			echo("      Issuer     : ${cert.issuerDN}")
			echo("      Valid from : ${cert.validFrom.formatDate()}")
			echo("      Valid to   : ${cert.validTo.formatDate()}")
			echo("      Token type : ${cert.tokenType}")
			val usages = cert.keyUsages.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "not specified"
			echo("      Key usages : $usages")
		}

		echo("")
		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 16))
		echo("  Use 'sign --certificate <alias>' to sign with a specific certificate.")
		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 16))
	}
}
