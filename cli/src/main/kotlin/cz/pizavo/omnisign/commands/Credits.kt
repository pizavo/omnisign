package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import cz.pizavo.omnisign.api.model.responses.CreditsResponse
import cz.pizavo.omnisign.cli.OutputConfig
import cz.pizavo.omnisign.legal.OMNISIGN_SOURCE_URL
import cz.pizavo.omnisign.legal.THIRD_PARTY_NOTICES_URL
import cz.pizavo.omnisign.legal.ThirdPartyComponent
import cz.pizavo.omnisign.legal.ThirdPartyCreditsReader
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Surface tag under which the generated credits list records what the CLI distributes. */
private const val CLI_SURFACE = "cli"

/** Width of the banner rules framing the listing, matching the other informational commands. */
private const val RULE_WIDTH = 63

/**
 * Writer that keeps the defaulted fields of [CreditsResponse], which carry the licence,
 * source and notice locations a consumer of `--json` is reading this command for.
 */
private val CreditsJson = Json { encodeDefaults = true }

/**
 * CLI subcommand that lists every third-party component this package distributes.
 *
 * The desktop and web applications show the same list in a Credits dialog. A CLI has no dialog,
 * and the licence texts it installs beside the executable are easy to never notice, so this
 * command is where a user can actually see what the tool is built on: it names each library, the
 * licence it is used under, its copyright and where its source is. Several of those licences —
 * the GNU LGPL that EU DSS is used under, above all — require exactly that to accompany every
 * copy of the work.
 *
 * The list is read from the generated credits resource on the classpath, filtered to the `cli`
 * surface, so it can only ever name what this package really ships: the server's and the desktop
 * app's own dependency sets are recorded in the same file but are not printed here.
 *
 * With `--json` the output is the [CreditsResponse] document the server's `GET /api/v1/credits`
 * returns, so both headless surfaces speak one format.
 */
class Credits : CliktCommand(name = "credits"), KoinComponent {
	private val creditsReader: ThirdPartyCreditsReader by inject()
	private val output by requireObject<OutputConfig>()

	override fun help(context: Context): String =
		"List the third-party components this package distributes, with their licences"

	override fun run() {
		val components = creditsReader.read(CLI_SURFACE)

		if (output.json) {
			echo(CreditsJson.encodeToString(CreditsResponse(components = components)))
		} else if (components.isNotEmpty()) {
			printCredits(components)
		}

		if (components.isEmpty()) {
			echo(
				"❌ No credits data found. This package was assembled without its generated " +
					"third-party notices; report it as a packaging bug.",
				err = true,
			)
			throw ProgramResult(1)
		}
	}

	/**
	 * Print the components grouped by licence, so a reader sees at a glance which terms apply and
	 * which file holds each licence's full text.
	 */
	private fun printCredits(components: List<ThirdPartyComponent>) {
		val rule = "═".repeat(RULE_WIDTH)

		echo(rule)
		echo("  THIRD-PARTY COMPONENTS (${components.size})")
		echo(rule)
		echo("")
		echo("  OmniSign is distributed under the GNU Affero General Public License")
		echo("  v3.0 or later. The components below are licensed by their own authors")
		echo("  under their own terms, separately from OmniSign.")

		components.groupBy { it.licenseName }.toSortedMap().forEach { (licenseName, entries) ->
			echo("")
			echo("  $licenseName")
			echo("  └─ full text in licenses/${entries.first().licenseText}")
			entries.forEach { component ->
				val noun = if (component.artifacts == 1) "artifact" else "artifacts"
				echo("")
				echo("     ${component.name} (${component.artifacts} $noun)")
				component.copyright?.let { echo("       $it") }
				component.homepage?.let { echo("       $it") }
			}
		}

		echo("")
		echo(rule)
		echo("  Licence texts : legal/licenses/ beside the executable, or")
		echo("                  META-INF/legal/licenses/ inside the JAR")
		echo("  Full notices  : $THIRD_PARTY_NOTICES_URL")
		echo("  Source        : $OMNISIGN_SOURCE_URL")
		echo(rule)
	}
}
