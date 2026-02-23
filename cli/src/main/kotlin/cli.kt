package cz.pizavo.omnisign

import com.github.ajalt.clikt.core.main
import com.github.ajalt.mordant.terminal.Terminal
import cz.pizavo.omnisign.cli.CliPasswordCallback
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.platform.PasswordCallback
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Main entry point for the CLI application.
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

	try {
		omnisignCli().main(args)
	} finally {
		stopKoin()
	}
}