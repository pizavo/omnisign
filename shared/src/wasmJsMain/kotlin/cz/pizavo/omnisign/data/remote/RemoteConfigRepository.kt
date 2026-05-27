package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.api.model.responses.GlobalConfigResponse
import cz.pizavo.omnisign.api.model.responses.ProfileConfigResponse
import cz.pizavo.omnisign.api.model.responses.toConfig
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Web-target [ConfigRepository] implementation that mirrors the server's
 * read-only configuration over HTTP.
 *
 * Fetches `GET /api/v1/config/global` and `GET /api/v1/config/profiles`, maps the
 * sanitized [GlobalConfigResponse] / [ProfileConfigResponse] DTOs back into the
 * domain [AppConfig] shape, and caches the result so every subsequent
 * [loadConfig] / [getCurrentConfig] call returns the cached value without hitting
 * the network again. Both entry points share the same in-memory cache because
 * `signing.yml` on the server is provider-authored and immutable within a
 * session — the JVM `FileConfigRepository`'s "always read fresh from disk"
 * semantics for [loadConfig] would translate to a redundant HTTP fetch per
 * ViewModel `init` on the web target.
 *
 * The rebuilt [AppConfig] reflects what the server is willing to share: JVM-only
 * fields ([GlobalConfig.customPkcs11Libraries], `pkcs11ProbeTimeoutSeconds`,
 * `trustedListRefreshIntervalHours`) keep their domain defaults, the timestamp
 * server is stripped of credential material via
 * [cz.pizavo.omnisign.api.model.responses.TimestampServerSummary.toConfig], and
 * the AppConfig-level fields that have no server-side analogue
 * (`activeProfile`, `tlDrafts`, `renewalJobs`, `schedulerConfig`) are left at
 * their empty defaults.
 *
 * [saveConfig] always returns [ConfigurationError.SaveFailed] because the
 * server's `signing.yml` is provider-authored and the web target has no write
 * surface against it. A `getCurrentConfig` failure surfaces as a synchronous
 * fallback to an empty [AppConfig], matching the JVM `FileConfigRepository`'s
 * behavior when its on-disk config is missing.
 *
 * @param client Pre-configured Ktor client with kotlinx-serialization content
 *   negotiation installed and a default request URL pointing at the OmniSign server.
 */
class RemoteConfigRepository(
    private val client: HttpClient,
) : ConfigRepository {

    private val mutex = Mutex()

    private var cachedConfig: AppConfig? = null

    override suspend fun loadConfig(): OperationResult<AppConfig> = mutex.withLock {
        cachedConfig?.let { return@withLock it.right() }
        fetchAndCache()
    }

    override suspend fun saveConfig(config: AppConfig): OperationResult<Unit> =
        ConfigurationError.SaveFailed(
            message = "Saving configuration is not supported on the web target",
            details = "The OmniSign server's configuration is provider-authored and read-only over the API",
        ).left()

    override suspend fun getCurrentConfig(): AppConfig = mutex.withLock {
        cachedConfig ?: fetchAndCache().fold(
            ifLeft = { AppConfig(global = GlobalConfig()) },
            ifRight = { it },
        )
    }

    /**
     * Issue the `GET /api/v1/config/global` and `GET /api/v1/config/profiles`
     * requests, build the sanitized [AppConfig], and populate [cachedConfig] on success.
     *
     * Must be called while holding [mutex] — both public entry points
     * ([loadConfig] and [getCurrentConfig]) wrap their invocation in `mutex.withLock`
     * so concurrent callers serialize and the second one reuses the cache instead
     * of issuing a duplicate pair of HTTP requests.
     */
    private suspend fun fetchAndCache(): OperationResult<AppConfig> =
        Either.catch {
            val global: GlobalConfigResponse = client.get("api/v1/config/global").body()
            val profiles: List<ProfileConfigResponse> = client.get("api/v1/config/profiles").body()
            AppConfig(
                global = global.toConfig(),
                profiles = profiles.associateBy { it.name }.mapValues { it.value.toConfig() },
            )
        }.fold(
            ifLeft = { exception ->
                ConfigurationError.LoadFailed(
                    message = "Failed to load configuration from server",
                    details = exception.message,
                    cause = exception,
                ).left()
            },
            ifRight = { config ->
                cachedConfig = config
                config.right()
            },
        )
}
