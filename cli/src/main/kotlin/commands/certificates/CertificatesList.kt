package cz.pizavo.omnisign.commands.certificates

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand that lists all certificates available from configured token sources
 * (PKCS#12 files, PKCS#11 hardware tokens, Windows keystore, macOS Keychain, etc.).
 *
 * The alias printed here is what should be supplied to `sign --certificate <alias>`.
 */
class CertificatesList : CliktCommand(name = "list"), KoinComponent {
	private val listCertificates: ListCertificatesUseCase by inject()
	
	override fun help(context: Context): String =
		"List all certificates available for signing"
	
	override fun run(): Unit = runBlocking {
		listCertificates().fold(
			ifLeft = { error ->
				echo("❌ Failed to list certificates: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.let { echo("Cause: ${it.message}", err = true) }
			},
			ifRight = { certificates ->
				if (certificates.isEmpty()) {
					echo("No certificates found. Configure a PKCS#12 file or hardware token via 'config set'.")
				} else {
					printCertificatesTable(certificates)
				}
			}
		)
	}
	
	/**
	 * Print a formatted table of available certificates to stdout.
	 */
	private fun printCertificatesTable(certificates: List<AvailableCertificateInfo>) {
		val aliasWidth = maxOf(certificates.maxOf { it.alias.length }, 5)
		val subjectWidth = maxOf(certificates.maxOf { it.subjectDN.length }, 7)
		val tokenWidth = maxOf(certificates.maxOf { it.tokenType.length }, 5)
		
		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 38))
		echo("  AVAILABLE CERTIFICATES (${certificates.size})")
		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 38))
		
		certificates.forEachIndexed { index, cert ->
			echo("")
			echo("  [${index + 1}] Alias      : ${cert.alias}")
			echo("      Subject    : ${cert.subjectDN}")
			echo("      Issuer     : ${cert.issuerDN}")
			echo("      Valid from : ${cert.validFrom}")
			echo("      Valid to   : ${cert.validTo}")
			echo("      Token type : ${cert.tokenType}")
			val usages = cert.keyUsages.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "not specified"
			echo("      Key usages : $usages")
		}
		
		echo("")
		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 38))
		echo("  Use 'sign --certificate <alias>' to sign with a specific certificate.")
		echo("═".repeat(aliasWidth + subjectWidth + tokenWidth + 38))
	}
}

