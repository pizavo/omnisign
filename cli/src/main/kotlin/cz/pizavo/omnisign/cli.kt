package cz.pizavo.omnisign

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.mordant.terminal.Terminal
import cz.pizavo.omnisign.cli.CliPasswordCallback
import cz.pizavo.omnisign.data.service.Pkcs11ProbeTimeout
import cz.pizavo.omnisign.data.service.Pkcs11WarmupService
import cz.pizavo.omnisign.di.appModule
import cz.pizavo.omnisign.di.jvmRepositoryModule
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.usecase.MigrateTrustedCertificatesUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform.getKoin
import java.util.Locale
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/**
 * Subcommand names whose execution requires PKCS#11 token discovery and therefore
 * benefit from background warmup.
 *
 * Only `sign` (which selects a signing certificate) and `certificates` (which lists
 * available tokens) actually invoke [cz.pizavo.omnisign.data.service.Pkcs11Discoverer];
 * all other subcommands skip warmup so the user is not delayed by the ~30 s probe cycle.
 */
private val WARMUP_SUBCOMMANDS = setOf("sign", "certificates")

/**
 * Main entry point for the CLI application.
 *
 * Starts Koin DI, runs the root [Omnisign] command, and converts
 * [ProgramResult] into the corresponding process exit code.
 *
 * The default locale is pinned to [Locale.ENGLISH] up front so the CLI is a stable,
 * locale-independent tool: its own output and any locale-driven text it surfaces (the DSS
 * validation report, renewal notifications) stay English regardless of the host's system locale.
 * The desktop app honors the user's chosen UI language separately.
 *
 * When the invoked subcommand needs PKCS#11 token discovery (`sign` or `certificates`),
 * a background warmup cycle is launched immediately after Koin starts so that
 * [Pkcs11WarmupService] can probe and pre-initialize middleware libraries while Clikt
 * parses the remaining arguments.  [Pkcs11Discoverer.discoverTokens] suspends on the
 * shared warmup-ready flow until warmup completes, ensuring the fast in-process probing
 * path is always available.
 *
 * When invoked with `probe <libraryPath>` (probe a single PKCS#11 library) or
 * `discover-modules` (enumerate the p11-kit-registered modules via libp11-kit), the app acts
 * as a thin wrapper around the corresponding isolated worker
 * ([cz.pizavo.omnisign.data.service.Pkcs11ProbeWorker] /
 * [cz.pizavo.omnisign.data.service.Pkcs11ModuleDiscoveryWorker]).  These modes are used by
 * the PKCS#11 discovery layer in jpackage distributions where the `java` binary is not
 * bundled in the runtime image.  Each exits immediately via [exitProcess] without starting
 * Koin or the Clikt command tree.
 *
 * [exitProcess] is called unconditionally so the JVM terminates immediately
 * even when third-party libraries (e.g., DSS's Apache HttpClient connection
 * pool) leave non-daemon threads alive after the operation completes.
 */
fun main(args: Array<String>) {
	Locale.setDefault(Locale.ENGLISH)
	if (args.size >= 2 && args[0] == "probe") {
		cz.pizavo.omnisign.data.service.Pkcs11ProbeWorker.main(args.drop(1).toTypedArray())
		exitProcess(0)
	}
	if (args.isNotEmpty() && args[0] == "discover-modules") {
		cz.pizavo.omnisign.data.service.Pkcs11ModuleDiscoveryWorker.main(args.drop(1).toTypedArray())
		exitProcess(0)
	}

	cz.pizavo.omnisign.data.repository.DssServiceFactory.enableAiaCaIssuerFetching()

	val terminal = Terminal()
	val needsWarmup = args.firstOrNull { !it.startsWith("-") } in WARMUP_SUBCOMMANDS

	startKoin {
		modules(
			appModule,
			jvmRepositoryModule,
			module {
				if (needsWarmup) single { MutableStateFlow(false) }
				single<PasswordCallback> { CliPasswordCallback(terminal) }
			},
		)
	}

	runBlocking {
		getKoin().get<MigrateTrustedCertificatesUseCase>()().fold(
			ifLeft = { logger.warn { "Trusted-certificate migration failed: ${it.message}" } },
			ifRight = { if (it > 0) logger.info { "Migrated $it inline trusted certificate(s) into the trust store" } },
		)
	}

	if (needsWarmup) {
		val koin = getKoin()
		CoroutineScope(Dispatchers.IO).launch {
			try {
				val warmupService = koin.get<Pkcs11WarmupService>()
				val configRepo = koin.get<ConfigRepository>()
				val config = configRepo.getCurrentConfig()
				koin.get<Pkcs11ProbeTimeout>().update(config.global.pkcs11ProbeTimeoutSeconds)
				val userLibs = config.global.customPkcs11Libraries.map { it.name to it.path }
				logger.info { "Launching PKCS#11 background warmup (${userLibs.size} user lib(s))" }
				warmupService.warmup(userPkcs11Libraries = userLibs)
			} catch (e: Exception) {
				logger.warn(e) { "PKCS#11 background warmup failed — certificate discovery will use subprocess probing" }
				koin.get<MutableStateFlow<Boolean>>().value = true
			}
		}
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
