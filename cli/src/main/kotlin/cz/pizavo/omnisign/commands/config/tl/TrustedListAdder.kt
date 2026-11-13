package cz.pizavo.omnisign.commands.config.tl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import cz.pizavo.omnisign.cli.failWith
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * CLI subcommand for registering a custom trusted list source.
 *
 * The source may be an HTTPS URL or a `file://`-prefixed path to a locally compiled
 * TL XML.  An optional PEM/DER certificate can be supplied that the TL's own XML
 * signature must verify against; a TL that does not verify supplies no trust.  Without
 * one, the command warns that the signature will not be verified, and also that a
 * plain `http://` source can be changed on the way.  When [profile] is specified the
 * entry is stored inside that profile's validation config; otherwise it is stored in
 * the global config.
 */
class TrustedListAdder : CliktCommand(name = "add"), KoinComponent {
	private val manageTl: ManageTrustedListsUseCase by inject()
	
	private val name by option(
		"--name", "-n",
		help = "Unique label for this trusted list (used in profiles and `tl remove`)"
	).required()
	
	private val source by option(
		"--source", "-s",
		help = "Source of the TL XML: HTTPS URL (e.g. https://example.com/tl.xml) " +
				"or a file path (e.g. /path/to/tl.xml)"
	).required()
	
	private val signingCert by option(
		"--signing-cert",
		help = "Path to the PEM or DER certificate the TL's XML signature must verify against; " +
				"a TL that does not verify is not used (strongly recommended for non-EU trusted lists)"
	).path(mustExist = true, canBeDir = false, mustBeReadable = true)
	
	private val profile by option(
		"--profile", "-p",
		help = "Store this trusted list in the given profile's validation config instead of the global config"
	)
	
	override fun help(context: Context): String =
		"Register a custom trusted list source (URL or local file)"
	
	override fun run(): Unit = runBlocking {
		val effectiveSource = resolveSource()
		val tl = CustomTrustedListConfig(
			name = name,
			source = effectiveSource,
			signingCertPath = signingCert?.toString()
		)
		manageTl.addTrustedList(tl, profile).fold(
			ifLeft = { failWith(it) },
			ifRight = {
				val scope = profile?.let { "profile '$it'" } ?: "global config"
				echo("✅ Trusted list '$name' registered in $scope.")
				if (tl.signingCertPath == null) {
					echo("⚠️  No signing certificate provided — TL signature will not be verified.")
					if (effectiveSource.startsWith("http://")) {
						echo(
							"⚠️  The TL is fetched over plain HTTP, so anyone on the network path can change " +
									"the certificates it lists. Use HTTPS or --signing-cert."
						)
					}
				}
			}
		)
	}
	
	/**
	 * Normalise a plain filesystem path to a `file://` URL so that DSS
	 * can load it consistently.  Absolute HTTPS/HTTP/file URLs are passed through unchanged.
	 */
	private fun resolveSource(): String {
		if (source.startsWith("http://") || source.startsWith("https://") ||
			source.startsWith("file://")
		) return source
		val file = File(source)
		return file.toURI().toString()
	}
}

