package cz.pizavo.omnisign

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import cz.pizavo.omnisign.cli.BuildConfig
import cz.pizavo.omnisign.cli.CliExtendedLoggers
import cz.pizavo.omnisign.cli.OutputConfig
import cz.pizavo.omnisign.cli.attachLogFileAppender
import cz.pizavo.omnisign.cli.extendedLibraryLevel
import cz.pizavo.omnisign.cli.rootLogLevel
import cz.pizavo.omnisign.commands.Renew
import cz.pizavo.omnisign.commands.Sign
import cz.pizavo.omnisign.commands.Timestamp
import cz.pizavo.omnisign.commands.Validate
import cz.pizavo.omnisign.commands.algorithms.Algorithms
import cz.pizavo.omnisign.commands.certificates.Certificates
import cz.pizavo.omnisign.commands.config.Config
import cz.pizavo.omnisign.commands.diagnose.Diagnose
import cz.pizavo.omnisign.commands.schedule.Schedule
import org.slf4j.LoggerFactory

/**
 * Main CLI entry point for Omnisign application.
 *
 * Provides global flags (`--json`, `--verbose`, `--debug`, `--debug-extended`,
 * `--quiet`) that are propagated to every subcommand via [OutputConfig] stored
 * in the Clikt context object, plus `--log-file` to also write this run's log
 * to a file. Verbosity is a three-tier ladder: default WARN, `--verbose` raises
 * stderr to INFO, `--debug` raises it to DEBUG, and `--debug-extended` also
 * lowers third-party library loggers (DSS, Apache) to DEBUG (it implies
 * `--debug`). The TSL loggers stay pinned at ERROR via `logback.xml` even
 * under `--debug-extended`.
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
		subcommands(Sign(), Validate(), Timestamp(), Renew(), Algorithms(), Certificates(), Config(), Diagnose(), Schedule())
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
		help = "Enable INFO-level logging to stderr"
	).flag(default = false)

	private val debug by option(
		"--debug",
		help = "Enable DEBUG-level logging to stderr (most detail)"
	).flag(default = false)

	private val debugExtended by option(
		"--debug-extended",
		help = "Also lower DSS/Apache library loggers to DEBUG (implies --debug)"
	).flag(default = false)

	private val quiet by option(
		"--quiet",
		help = "Suppress all informational output; only errors are printed"
	).flag(default = false)

	private val logFile by option(
		"--log-file",
		help = "Also write this run's log to PATH (appended; in addition to stderr)",
		metavar = "PATH",
	)

	override fun help(context: Context): String =
		"Digital signature verification, signing and re-timestamping tool"
	
	override fun run() {
		(LoggerFactory.getILoggerFactory() as? LoggerContext)?.let { ctx ->
			rootLogLevel(verbose = verbose, debug = debug, extended = debugExtended)?.let { target ->
				ctx.getLogger(Logger.ROOT_LOGGER_NAME).level = target
			}
			extendedLibraryLevel(extended = debugExtended)?.let { target ->
				for (name in CliExtendedLoggers) ctx.getLogger(name).level = target
			}
		}

		logFile?.let { path ->
			if (!attachLogFileAppender(path)) {
				echo("Warning: could not open log file '$path'; logging to stderr only.", err = true)
			}
		}

		currentContext.findOrSetObject {
			OutputConfig(
				json = json,
				verbose = verbose || debug || debugExtended,
				debug = debug || debugExtended,
				extended = debugExtended,
				quiet = quiet,
			)
		}
		
		if (currentContext.invokedSubcommand == null) echo(getFormattedHelp())
	}
}
