package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.service.Pkcs11CandidateCollector
import cz.pizavo.omnisign.data.service.Pkcs11ProbeTimeout
import cz.pizavo.omnisign.data.service.Pkcs11Prober
import cz.pizavo.omnisign.data.service.Pkcs11WarmupService
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies that the shared JVM graph builds the [Pkcs11WarmupService] with the process's one
 * [Pkcs11ProbeTimeout], so the timeout a warmup applies from the configuration is the one every
 * later probe of the process reads, those of certificate discovery included.
 *
 * The service makes a holder of its own when it is given none, so a definition that left it out
 * would still resolve, and [JvmKoinGraphTest] would still pass. The candidate collector is
 * replaced with a mock that finds no library, so the warmup probes nothing, and the prober with a
 * mock that is never called; the service is built by the graph's own definition.
 */
class JvmWarmupProbeTimeoutBindingTest : FunSpec({

	test("a warmup of the configuration sets the probe timeout the rest of the graph reads") {
		val collector = mockk<Pkcs11CandidateCollector> {
			every { collectCandidates(any(), any()) } returns emptyList()
		}
		val application = koinApplication {
			allowOverride(true)
			modules(
				jvmRepositoryModule,
				module {
					single { collector }
					single<Pkcs11Prober> { mockk() }
					single { MutableStateFlow(false) }
				},
			)
		}
		try {
			application.koin.get<Pkcs11WarmupService>()
				.warmup(AppConfig(global = GlobalConfig(pkcs11ProbeTimeoutSeconds = 45)))

			application.koin.get<Pkcs11ProbeTimeout>().seconds shouldBe 45L
		} finally {
			application.close()
		}
	}
})
