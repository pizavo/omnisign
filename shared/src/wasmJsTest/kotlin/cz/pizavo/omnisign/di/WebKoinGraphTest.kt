package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.remote.BrowserProfileSelectionStore
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.usecase.*
import cz.pizavo.omnisign.testing.RecordingProfileSelectionStore
import cz.pizavo.omnisign.web.auth.WebAuthApi
import cz.pizavo.omnisign.web.auth.WebAuthState
import cz.pizavo.omnisign.web.auth.WebSessionState
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Verifies the DI graph the web target boots — `appModule + webDataModule` plus the entry point's
 * own platform bindings — actually builds every component the app resolves.
 *
 * The JVM graphs are checked by reflection
 * ([cz.pizavo.omnisign.di.JvmKoinGraphTest], [cz.pizavo.omnisign.di.ServerKoinGraphTest]), which is
 * not an option here: Koin's `verify` is JVM-only. This spec instead resolves the graph for real in
 * the browser, which is the stronger check anyway — it constructs each component rather than
 * inspecting constructors, so it also catches the interface-bound definitions reflection skips.
 *
 * Deliberately built on plain `koin-core` rather than `koin-test`. Koin's test artifact depends on a
 * `kotlin-test` built against an older Kotlin, whose klib ABI the current compiler rejects; pulling
 * it into this source set would break the Wasm test compilation outright.
 *
 * Each case builds an isolated [koinApplication] rather than calling `startKoin`, so no global Koin
 * context is left behind for the next spec to trip over.
 */
class WebKoinGraphTest : FunSpec({

	/**
	 * The web graph as `main.kt` composes it: the shared use cases, the HTTP-backed data layer, and
	 * the browser-side profile store the entry point contributes.
	 */
	fun webKoin(): Koin = koinApplication {
		modules(
			appModule,
			webDataModule(serverBaseUrl = "https://omnisign.test"),
			module { single<BrowserProfileSelectionStore> { RecordingProfileSelectionStore() } },
		)
	}.koin

	test("builds every port the web data layer promises") {
		val koin = webKoin()

		koin.get<CapabilitiesRepository>()
		koin.get<ValidationRepository>()
		koin.get<ConfigRepository>()
		koin.get<SigningRepository>()
		koin.get<ArchivingRepository>()
		koin.get<TrustStore>()
		koin.get<ConfigArchivePort>()
	}

	test("builds every use case the shared module exposes") {
		val koin = webKoin()

		koin.get<AlgorithmExpirationChecker>()
		koin.get<ValidateDocumentUseCase>()
		koin.get<SignDocumentUseCase>()
		koin.get<ListCertificatesUseCase>()
		koin.get<UnlockTokenUseCase>()
		koin.get<LoadFileCertificatesUseCase>()
		koin.get<ListKeystoreCertificatesUseCase>()
		koin.get<ExtendDocumentUseCase>()
		koin.get<CheckArchivalRenewalUseCase>()
		koin.get<GetDocumentTimestampInfoUseCase>()
		koin.get<GetConfigUseCase>()
		koin.get<SetGlobalConfigUseCase>()
		koin.get<ManageProfileUseCase>()
		koin.get<ManageTrustedListsUseCase>()
		koin.get<ManageRenewalJobsUseCase>()
		koin.get<ManagePkcs11LibrariesUseCase>()
	}

	test("builds the login-flow components and both HTTP clients") {
		val koin = webKoin()

		koin.get<WebAuthState>()
		koin.get<WebSessionState>()
		koin.get<WebAuthApi>()
		koin.get<HttpClient>()
		koin.get<HttpClient>(named(AUTH_HTTP_CLIENT))
	}

	test("keeps the bearer-carrying and bare HTTP clients distinct") {
		val koin = webKoin()

		val api = koin.get<HttpClient>()
		val bare = koin.get<HttpClient>(named(AUTH_HTTP_CLIENT))

		(api === bare) shouldBe false
	}

	test("leaves the browser profile store to the entry point rather than declaring it") {
		val withoutStore = koinApplication {
			modules(appModule, webDataModule(serverBaseUrl = "https://omnisign.test"))
		}.koin

		shouldThrowAny { withoutStore.get<ConfigRepository>() }
	}
})
