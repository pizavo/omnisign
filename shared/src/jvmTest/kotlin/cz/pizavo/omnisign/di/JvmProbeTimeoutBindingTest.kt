package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.service.PcscMonitorService
import cz.pizavo.omnisign.data.service.Pkcs11CacheInvalidator
import cz.pizavo.omnisign.data.service.Pkcs11CandidateCollector
import cz.pizavo.omnisign.data.service.Pkcs11Discoverer
import cz.pizavo.omnisign.data.service.Pkcs11ProbeCache
import cz.pizavo.omnisign.data.service.Pkcs11ProbeTimeout
import cz.pizavo.omnisign.data.service.Pkcs11Prober
import cz.pizavo.omnisign.data.service.Pkcs11SubprocessResult
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies that the shared JVM graph hands the [TokenService] the [Pkcs11ProbeTimeout] it shares
 * with the other probe-spawning services, so the probe behind its no-login certificate listing is
 * killed after the configured `pkcs11ProbeTimeoutSeconds`.
 *
 * The token service falls back to a holder of its own when none is given, so a definition that
 * left the shared one out would still resolve, and [JvmKoinGraphTest] would still pass. The
 * collaborators that would reach the PC/SC stack, the PKCS#11 libraries or the configuration on
 * disk are replaced with mocks; the token service is built by the graph's own definition.
 */
class JvmProbeTimeoutBindingTest : FunSpec({

	test("the token service's no-login listing kills its probe after the timeout the graph shares") {
		val libraryPath = "/usr/lib/middleware-pkcs11.so"
		val prober = mockk<Pkcs11Prober> {
			every { runCertProbe(any(), any()) } returns Pkcs11SubprocessResult.Success(pid = 1L, stdout = "")
			every { parseIdentities(any(), any()) } returns emptyList()
		}
		val application = koinApplication {
			allowOverride(true)
			modules(
				jvmRepositoryModule,
				module {
					single<PasswordCallback> { mockk() }
					single<Pkcs11Prober> { prober }
					single<ConfigRepository> { mockk() }
					single { mockk<Pkcs11ProbeCache>() }
					single { mockk<Pkcs11CandidateCollector>() }
					single { mockk<Pkcs11Discoverer> { every { discoveryRunning } returns MutableStateFlow(false) } }
					single { mockk<Pkcs11CacheInvalidator>() }
					single { mockk<PcscMonitorService>() }
				},
			)
		}
		try {
			application.koin.get<Pkcs11ProbeTimeout>().update(42)
			application.koin.get<TokenService>().listCertificatesNoLogin(
				TokenInfo(
					id = "pkcs11-SN-A", name = "Card", type = TokenType.PKCS11,
					path = libraryPath, requiresPin = true, pkcs11SlotId = 1L,
				)
			)
		} finally {
			application.close()
		}

		verify(exactly = 1) { prober.runCertProbe(libraryPath, 42) }
	}
})
