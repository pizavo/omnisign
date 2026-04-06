package cz.pizavo.omnisign

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import cz.pizavo.omnisign.cli.BuildConfig
import cz.pizavo.omnisign.cli.OutputConfig
import cz.pizavo.omnisign.commands.Renew
import cz.pizavo.omnisign.commands.Sign
import cz.pizavo.omnisign.commands.Timestamp
import cz.pizavo.omnisign.commands.Validate
import cz.pizavo.omnisign.commands.algorithms.Algorithms
import cz.pizavo.omnisign.commands.certificates.Certificates
import cz.pizavo.omnisign.commands.config.Config
import cz.pizavo.omnisign.commands.schedule.Schedule
import org.slf4j.LoggerFactory

/**
 * Main CLI entry point for Omnisign application.
 *
 * Provides global flags (`--json`, `--verbose`, `--quiet`) that are propagated to
 * every subcommand via [OutputConfig] stored in the Clikt context object.
 *
 * **Security note — environment variable prefix:** The [Context.autoEnvvarPrefix] is set to
 * `OMNISIGN`, meaning every CLI option can be supplied via an `OMNISIGN_`-prefixed environment
 * variable (e.g. `OMNISIGN_TIMESTAMP_PASSWORD`). On Linux, environment variables of a running
 * process are readable via `/proc/<pid>/environ` by the same user. This is standard Clikt
 * behavior and consistent with industry practice (Docker, AWS CLI, etc.), but operators should
 * be aware that secrets passed this way are not protected from local same-user inspection.
 * Prefer using `--timestamp-password -` (interactive hidden prompt) or the OS credential store
 * (`config set --timestamp-password -`) for sensitive values.
 */
class Omnisign : CliktCommand(name = "omnisign") {
	init {
		versionOption(BuildConfig.VERSION, names = setOf("-v", "--version"))
		subcommands(Sign(), Validate(), Timestamp(), Renew(), Algorithms(), Certificates(), Config(), Schedule())
		context {
			autoEnvvarPrefix = "OMNISIGN"
		}
	}
	
	private val json by option(
		"--json",
		help = "Emit machine-readable JSON output instead of human-readable text"
	).flag(default = false)
	
	private val verbose by option(
		"--verbose",
		help = "Enable verbose (DEBUG-level) logging to stderr"
	).flag(default = false)
	
	private val quiet by option(
		"--quiet",
		help = "Suppress all informational output; only errors are printed"
	).flag(default = false)
	
	override fun help(context: Context): String =
		"Digital signature verification, signing and re-timestamping tool"
	
	override fun run() {
		if (verbose) {
			val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
			root.level = Level.DEBUG
		}
		
		currentContext.findOrSetObject { OutputConfig(json = json, verbose = verbose, quiet = quiet) }
		
		if (currentContext.invokedSubcommand == null) echo(getFormattedHelp())
	}
}
