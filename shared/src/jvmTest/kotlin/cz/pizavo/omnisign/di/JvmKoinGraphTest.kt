package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.Verification

/**
 * Verifies the DI graph shared by every JVM entry point — the CLI, the desktop app, and the server
 * all start from `appModule + jvmRepositoryModule` — declares a home for every dependency it asks
 * for.
 *
 * Koin resolves lazily, so a missing or mistyped binding stays invisible until the first component
 * that needs it is requested: a `NoDefinitionFoundException` at application start, or later still on
 * the first use of a command that happens to reach it. Reflecting over the graph turns that into a
 * compile-adjacent failure here instead.
 *
 * [PasswordCallback] is declared as an extra type rather than a definition because it is the
 * documented platform boundary each entry point fills in itself (terminal prompt, Compose dialog, or
 * the server's non-interactive null), and `MutableStateFlow` because the PKCS#11 warm-up signal is
 * resolved with `getOrNull` and supplied only by the interactive front ends.
 *
 * The [Verification] objects are merged before verifying rather than run through Koin's `verifyAll`,
 * which is a plain `forEach { module.verify() }` and so checks each module against its own
 * definitions alone. Under it, every use case in [appModule] would be reported as missing the
 * repository that [jvmRepositoryModule] binds — a false failure whose obvious "fix", widening
 * `extraTypes` until it passes, would suppress the real defects this spec exists to catch.
 */
@OptIn(KoinExperimentalAPI::class)
class JvmKoinGraphTest : FunSpec({

	val extraTypes = listOf(
		PasswordCallback::class,
		MutableStateFlow::class,
		File::class,
		Path::class,
		List::class,
	)

	test("the shared JVM graph resolves every dependency it declares") {
		val graph = Verification(appModule, extraTypes) + Verification(jvmRepositoryModule, extraTypes)

		graph.verify()
	}
})
