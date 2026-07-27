package cz.pizavo.omnisign.data.remote

import cz.pizavo.omnisign.api.model.responses.GlobalConfigResponse
import cz.pizavo.omnisign.api.model.responses.ProfileConfigResponse
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.service.CrlConfig
import cz.pizavo.omnisign.domain.model.config.service.OcspConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.testing.RecordingProfileSelectionStore
import cz.pizavo.omnisign.testing.mockApiClient
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json

/**
 * Verifies [RemoteConfigRepository] mirrors the server's read-only configuration: it fetches the
 * global and profile documents once and serves every later caller from its cache, layers the
 * browser-side active-profile selection on top (dropping one the server no longer offers), and
 * refuses to write back to a provider-authored configuration.
 */
class RemoteConfigRepositoryTest : FunSpec({

	val globalJson = Json.encodeToString(
		GlobalConfigResponse(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultEncryptionAlgorithm = null,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T,
			disabledHashAlgorithms = emptySet(),
			disabledEncryptionAlgorithms = emptySet(),
			timestampServer = null,
			ocsp = OcspConfig(),
			crl = CrlConfig(),
			validation = ValidationConfig(),
		),
	)

	/**
	 * A client answering the two configuration routes, counting the requests it served into
	 * [counter] so a spec can prove the cache spared the network.
	 */
	fun configClient(counter: IntArray, profileNames: List<String> = listOf("qualified")): HttpClient =
		mockApiClient { request ->
			counter[0]++
			val json = if (request.url.encodedPath.endsWith("/profiles")) {
				Json.encodeToString(
					profileNames.map { name ->
						ProfileConfigResponse(
							name = name,
							description = null,
							hashAlgorithm = null,
							encryptionAlgorithm = null,
							disabledHashAlgorithms = emptySet(),
							disabledEncryptionAlgorithms = emptySet(),
							signatureLevel = null,
							timestampServer = null,
							ocsp = null,
							crl = null,
							validation = null,
						)
					},
				)
			} else {
				globalJson
			}
			respond(
				content = json,
				headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
			)
		}

	test("builds the config from the global and profile routes") {
		val requests = intArrayOf(0)
		val repository = RemoteConfigRepository(configClient(requests), RecordingProfileSelectionStore())

		val config = repository.loadConfig().shouldBeRight()

		requests[0] shouldBe 2
		config.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA256
		config.global.defaultSignatureLevel shouldBe SignatureLevel.PADES_BASELINE_T
		config.profiles.keys shouldBe setOf("qualified")
		config.activeProfile shouldBe null
	}

	test("serves a second load from the cache instead of refetching") {
		val requests = intArrayOf(0)
		val repository = RemoteConfigRepository(configClient(requests), RecordingProfileSelectionStore())

		repository.loadConfig().shouldBeRight()
		repository.loadConfig().shouldBeRight()
		repository.getCurrentConfig()

		requests[0] shouldBe 2
	}

	test("layers the persisted active profile onto the server's config") {
		val store = RecordingProfileSelectionStore(initial = "qualified")
		val repository = RemoteConfigRepository(configClient(intArrayOf(0)), store)

		repository.loadConfig().shouldBeRight().activeProfile shouldBe "qualified"

		store.writes shouldContainExactly emptyList()
	}

	test("drops a persisted profile the server no longer offers") {
		val store = RecordingProfileSelectionStore(initial = "retired")
		val repository = RemoteConfigRepository(
			configClient(intArrayOf(0), profileNames = listOf("qualified")),
			store,
		)

		repository.loadConfig().shouldBeRight().activeProfile shouldBe null

		store.writes shouldContainExactly listOf(null)
		store.stored shouldBe null
	}

	test("persists a new active profile client-side and reflects it in the cache") {
		val store = RecordingProfileSelectionStore()
		val repository = RemoteConfigRepository(configClient(intArrayOf(0)), store)
		repository.loadConfig().shouldBeRight()

		repository.setActiveProfile("qualified").shouldBeRight()

		store.stored shouldBe "qualified"
		repository.getCurrentConfig().activeProfile shouldBe "qualified"
	}

	test("refuses to save because the server's configuration is provider-authored") {
		val repository = RemoteConfigRepository(configClient(intArrayOf(0)), RecordingProfileSelectionStore())
		val config = repository.loadConfig().shouldBeRight()

		repository.saveConfig(config).shouldBeLeft()
	}

	test("maps a failed fetch to a configuration error") {
		val repository = RemoteConfigRepository(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.ServiceUnavailable) },
			RecordingProfileSelectionStore(),
		)

		repository.loadConfig().shouldBeLeft()
	}

	test("falls back to an empty config when getCurrentConfig cannot reach the server") {
		val repository = RemoteConfigRepository(
			mockApiClient { respond(content = "boom", status = HttpStatusCode.ServiceUnavailable) },
			RecordingProfileSelectionStore(),
		)

		val config = repository.getCurrentConfig()

		config.profiles shouldBe emptyMap()
		config.activeProfile shouldBe null
	}
})
