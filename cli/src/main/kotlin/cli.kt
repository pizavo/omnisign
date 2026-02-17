package cz.pizavo.omnisign

import com.github.ajalt.clikt.core.main
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import org.koin.core.context.startKoin

/**
 * Main entry point for the CLI application.
 */
fun main(args: Array<String>) {
	startKoin {
		modules(appModule, jvmRepositoryModule)
	}
	
	omnisignCli().main(args)
}