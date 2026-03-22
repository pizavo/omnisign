package cz.pizavo.omnisign

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.mordant.terminal.Terminal
import cz.pizavo.omnisign.cli.CliPasswordCallback
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.platform.PasswordCallback
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.system.exitProcess

/**
 * Main entry point for the CLI application.
 *
 * Starts Koin DI, runs the root [Omnisign] command, and converts
 * [ProgramResult] into the corresponding process exit code.
 *
 * [exitProcess] is called unconditionally so the JVM terminates immediately
 * even when third-party libraries (e.g., DSS's Apache HttpClient connection
 * pool) leave non-daemon threads alive after the operation completes.
 */
fun main(args: Array<String>) {
	val terminal = Terminal()
	
	startKoin {
		modules(
			appModule,
			jvmRepositoryModule,
			module { single<PasswordCallback> { CliPasswordCallback(terminal) } }
		)
	}
	
	var exitCode = 0
	try {
		Omnisign().main(args)
	} catch (e: ProgramResult) {
		exitCode = e.statusCode
	} finally {
		stopKoin()
	}
	exitProcess(exitCode)
}
