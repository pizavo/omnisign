package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.repository.DssLogCapture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.koin.dsl.koinApplication

/**
 * Verifies that the JVM DI graph hands every repository the same [DssLogCapture].
 *
 * The capture coordinates the sessions of overlapping operations on the process-wide
 * `eu.europa.esig` logger, and separate instances do not coordinate with each other, so it has to be
 * a single rather than a factory.
 */
class DssLogCaptureBindingTest : FunSpec({

	test("the JVM graph has one DSS log capture") {
		val application = koinApplication { modules(appModule, jvmRepositoryModule) }
		try {
			application.koin.get<DssLogCapture>() shouldBeSameInstanceAs application.koin.get<DssLogCapture>()
		} finally {
			application.close()
		}
	}
})
