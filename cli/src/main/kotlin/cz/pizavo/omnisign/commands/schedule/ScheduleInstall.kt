package cz.pizavo.omnisign.commands.schedule

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import cz.pizavo.omnisign.data.service.OsSchedulerService
import cz.pizavo.omnisign.data.service.SelfExecutableResolver
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Registers (or replaces) the daily `omnisign renew` OS job.
 *
 * The CLI executable path is resolved automatically from the current process.  When the
 * process was launched via `java -jar`, the path cannot be determined unambiguously and
 * `--cli-path` must be supplied explicitly.
 */
class ScheduleInstall : CliktCommand(name = "install"), KoinComponent {
	private val scheduler: OsSchedulerService by inject()
	
	private val cliPath by option(
		"--cli-path",
		help = "Absolute path to the omnisign binary. Auto-detected when omitted (may fail for java -jar invocations)."
	)
	
	private val hour by option(
		"--hour",
		help = "Hour of day (0-23) at which the daily job runs. Default: 2"
	).int().default(2)
	
	private val minute by option(
		"--minute",
		help = "Minute (0-59) at which the daily job runs. Default: 0"
	).int().default(0)
	
	private val logFile by option(
		"--log-file",
		help = "Absolute path to an append-only log file for renewal run output."
	)
	
	override fun help(context: Context): String =
		"Register the daily automatic re-timestamping job with the OS scheduler"
	
	override fun run() {
		val resolvedCliPath = cliPath ?: SelfExecutableResolver.resolve()
		if (resolvedCliPath == null) {
			echo(
				"❌ Could not auto-detect the CLI executable path.\n" +
						"Please supply it explicitly with --cli-path.",
				err = true
			)
			throw ProgramResult(1)
		}
		
		scheduler.install(
			cliExecutablePath = resolvedCliPath,
			runAtHour = hour,
			runAtMinute = minute,
			logFilePath = logFile,
		)
		
		echo("✅ Daily renewal job installed.")
		echo("   Binary : $resolvedCliPath")
		echo("   Time   : %02d:%02d".format(hour, minute))
		logFile?.let { echo("   Log    : $it") }
	}
}

