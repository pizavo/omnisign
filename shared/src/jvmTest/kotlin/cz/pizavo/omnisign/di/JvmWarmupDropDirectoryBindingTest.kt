package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.service.Pkcs11CandidateCollector
import cz.pizavo.omnisign.data.service.Pkcs11Prober
import cz.pizavo.omnisign.data.service.Pkcs11WarmupService
import cz.pizavo.omnisign.data.service.pkcs11DropDir
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies that the shared JVM graph builds the [Pkcs11WarmupService] with the PKCS#11 drop
 * directory [pkcs11DropDir] resolves, so the warmup of every entry point, the CLI's included,
 * validates the libraries copied there.
 *
 * The service leaves the drop directory out when it is given none, so a definition that left it
 * out would still resolve, and [JvmKoinGraphTest] would still pass. The candidate collector is
 * replaced with a mock that finds no library, so the warmup probes nothing, and the prober with a
 * mock that is never called; the service is built by the graph's own definition.
 */
class JvmWarmupDropDirectoryBindingTest : FunSpec({

	test("the warmup service the graph builds collects the candidates of the drop directory") {
		val userLibraries = listOf("Vendor" to "/usr/lib/vendor-pkcs11.so")
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
			application.koin.get<Pkcs11WarmupService>().warmup(userPkcs11Libraries = userLibraries)
		} finally {
			application.close()
		}

		verify(exactly = 1) { collector.collectCandidates(pkcs11DropDir(), userLibraries) }
	}
})
