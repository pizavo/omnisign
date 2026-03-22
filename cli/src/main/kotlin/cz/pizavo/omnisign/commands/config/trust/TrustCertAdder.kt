package cz.pizavo.omnisign.commands.config.trust

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.data.service.TrustedCertificateReader
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for registering a directly trusted certificate.
 *
 * The certificate file is read and parsed at registration time; its DER bytes and
 * subject DN are stored inline in the config. The original file is no longer needed.
 */
class TrustCertAdder : CliktCommand(name = "add"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val name by option(
		"--name", "-n",
		help = "Unique label for this trusted certificate"
	).required()
	
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
		help = "Store in the given profile instead of the global config"
	)
	
	override fun help(context: Context): String =
		"Trust a certificate directly (no TL XML required)"
	
	@Suppress("TooGenericExceptionCaught")
	override fun run(): Unit = runBlocking {
		val certConfig = try {
			TrustedCertificateReader.read(name, cert.toFile(), type)
		} catch (e: Exception) {
			echo("❌ Failed to read certificate: ${e.message}", err = true)
			return@runBlocking
		}
		
		manageTl.addTrustedCertificate(certConfig, profile).fold(
			ifLeft = { error ->
				echo("❌ ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
			},
			ifRight = {
				val scope = profile?.let { "profile '$it'" } ?: "global config"
				echo("✅ Trusted ${type.name} certificate '$name' added to $scope.")
				echo("   Subject: ${certConfig.subjectDN}")
			}
		)
	}
}

