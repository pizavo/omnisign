package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.auth.ExposedHandoffCodeStore
import cz.pizavo.omnisign.auth.ExposedLoginRequestStore
import cz.pizavo.omnisign.auth.ExposedPkceVerifierStore
import cz.pizavo.omnisign.auth.ExposedRefreshTokenStore
import cz.pizavo.omnisign.auth.HandoffCodeStore
import cz.pizavo.omnisign.auth.IdTokenVerifier
import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.auth.LoginRequestStore
import cz.pizavo.omnisign.auth.OidcDiscoveryService
import cz.pizavo.omnisign.auth.OidcUserInfoService
import cz.pizavo.omnisign.auth.PkceService
import cz.pizavo.omnisign.auth.PkceVerifierStore
import cz.pizavo.omnisign.auth.RefreshTokenStore
import cz.pizavo.omnisign.auth.ServerPasswordCallback
import cz.pizavo.omnisign.auth.sessionsDbFile
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ReadOnlyConfigRepository
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerConfigLoader
import cz.pizavo.omnisign.config.ServerSecrets
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.config.TrustReconciler
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.platform.PasswordCallback
import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.NonceManager
import io.ktor.util.StatelessHmacNonceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/**
 * Server-specific Koin module.
 *
 * Provides:
 * - [ServerConfig] singleton from the preloaded configuration.
 * - [ServerConfigLoader] singleton.
 * - [PasswordCallback] that always returns `null` (server cannot prompt interactively).
 * - [ConfigRepository] (read-only [ReadOnlyConfigRepository]) backed by the provider's
 *   signing.yml, overriding the home-directory `FileConfigRepository` from
 *   `jvmRepositoryModule` so the server never reads or writes the user's config file.
 * - [TrustStore] (content-addressed [FileTrustStore]) rooted at the server's writable trust
 *   directory ([ServerConfig.trustStoreDir], or a server-local default), overriding the
 *   `jvmRepositoryModule` binding so the server never touches a co-located desktop trust store.
 * - [TrustReconciler] that provisions that trust store from the signing.yml trust references at
 *   boot; invoked from `Application.moduleWith`.
 * - IO [CoroutineContext] for blocking work.
 * - [HttpClient] (CIO engine) with JSON content-negotiation and per-stage timeouts
 *   ([HttpTimeout]) for OIDC discovery and user-info requests. The timeouts close the
 *   availability hit where a slow or hung IdP would otherwise pin a request thread
 *   indefinitely and drain the CIO connection pool.
 * - [JwtSessionService] for issuing and verifying session tokens. The signing secret is
 *   resolved from the `OMNISIGN_JWT_SECRET` environment variable at startup (see
 *   [ServerSecrets]). When `auth.enabled` is `true` the env var is required and the
 *   resolved value must be at least [MIN_JWT_SECRET_BYTES] bytes — startup fails
 *   otherwise. When `auth.enabled` is `false` the env var may be omitted;
 *   [JwtSessionService] becomes inert (`verify` returns `null` for every token,
 *   `issue` throws if called).
 * - [NonceManager] for OAuth2 `state`-parameter CSRF protection on the authorization-code
 *   callback. Implemented as [StatelessHmacNonceManager]; the HMAC key is read from
 *   `auth.oauthStateSecret` (typically declared via env-var substitution:
 *   `oauthStateSecret: "${OMNISIGN_OAUTH_NONCE_SECRET}"`). The binding is lazy and only
 *   resolves when an OIDC provider's `oauth { … }` block actually needs it; deployments
 *   without OIDC providers never hit the resolution and never need to configure the field.
 *   When the binding does resolve the field is required and must be at least
 *   [MIN_NONCE_KEY_BYTES] bytes — startup fails otherwise.
 * - [Database] singleton: SQLite connection rooted at [sessionsDbFile]. Created lazily
 *   on first injection; the parent directory is created if it does not exist. Each
 *   connection sets `PRAGMA busy_timeout` ([SQLITE_BUSY_TIMEOUT_MS]) so that when two
 *   session writes contend for the same row — two browser tabs firing `/auth/refresh` on
 *   the same cookie at once — the loser waits for the winner to commit and then reads an
 *   already-consumed row (a clean `401`), rather than failing immediately with `SQLITE_BUSY`
 *   (a `500`). Exposed runs SQLite at `SERIALIZABLE`, so without the timeout the contended
 *   write has no grace period at all.
 * - [RefreshTokenStore] (concrete [ExposedRefreshTokenStore]) for persisting refresh
 *   tokens across server restarts. Schema is initialised on first injection; the
 *   binding is lazy so deployments that never reach `/auth/refresh` or `/auth/logout`
 *   never touch SQLite or write the file to disk.
 * - [PkceVerifierStore] (concrete [ExposedPkceVerifierStore]) for persisting in-flight
 *   PKCE code verifiers across the redirect → IdP → callback hop. Shares the same
 *   sessions database file as [RefreshTokenStore]; schema is initialised on first
 *   injection. Binding is lazy so deployments without any [OidcProviderConfig] never
 *   touch SQLite for PKCE either.
 * - [PkceService] (RFC 7636) — verifier generation, S256 challenge derivation, and the
 *   thin protocol layer over [PkceVerifierStore].
 * - [LoginRequestStore] (concrete [ExposedLoginRequestStore]) and [HandoffCodeStore]
 *   (concrete [ExposedHandoffCodeStore]) for the browser hand-off: the first parks what a
 *   single-page app asked for across the IdP round trip, the second carries the finished
 *   login back to the app's own origin. Both share the sessions database file with
 *   [RefreshTokenStore]; schemas are initialised on first injection, and the bindings are
 *   lazy so a deployment with no browser front-end never creates either table.
 * - [OidcDiscoveryService] and [OidcUserInfoService] for the OIDC authorization-code flow.
 * - [IdTokenVerifier] for cryptographic verification of the OIDC `id_token` returned by
 *   the IdP alongside the access token on the authorization-code callback.
 *
 * @param serverConfig Preloaded server configuration.
 * @param secrets Secret values resolved from environment variables.
 * @param signingConfig Provider signing/validation policy resolved from signing.yml at
 *   startup, exposed through the read-only [ConfigRepository] binding.
 * @param httpClientEngine Test-only override for the outbound [HttpClient]'s engine — a `MockEngine`
 *   standing in for the IdP. Production passes `null`, which uses the CIO engine.
 */
fun serverModule(serverConfig: ServerConfig, secrets: ServerSecrets, signingConfig: AppConfig, httpClientEngine: HttpClientEngine? = null) = module {
	single<ServerConfig> { serverConfig }
	single<ServerSecrets> { secrets }
	single<ServerConfigLoader> { ServerConfigLoader() }
	single<PasswordCallback> { ServerPasswordCallback() }
	single<ConfigRepository> { ReadOnlyConfigRepository(signingConfig) }
	single<CoroutineContext> { Dispatchers.IO }

	single<TrustStore> { FileTrustStore(serverTrustDir(serverConfig)) }
	single { TrustReconciler(get()) }

	if (AllowedOperation.SIGN in serverConfig.operations.allowed) {
		single { MutableStateFlow(false) }
	}

	single<HttpClient> {
		val engine = httpClientEngine ?: CIO.create()
		HttpClient(engine) {
			install(ContentNegotiation) {
				json(Json { ignoreUnknownKeys = true })
			}
			install(HttpTimeout) {
				requestTimeoutMillis = IDP_REQUEST_TIMEOUT_MS
				connectTimeoutMillis = IDP_CONNECT_TIMEOUT_MS
				socketTimeoutMillis = IDP_SOCKET_TIMEOUT_MS
			}
		}
	}

	single<OidcDiscoveryService> { OidcDiscoveryService(get()) }
	single<OidcUserInfoService> { OidcUserInfoService(get()) }
	single<IdTokenVerifier> { IdTokenVerifier(get()) }

	single<JwtSessionService> {
		val config = serverConfig.auth?.session ?: SessionConfig()
		val secret = secrets.jwtSecret
		if (secret != null) {
			val secretBytes = secret.value.toByteArray(Charsets.UTF_8).size
			require(secretBytes >= MIN_JWT_SECRET_BYTES) {
				"${ServerSecrets.JWT_SECRET_ENV} must be at least $MIN_JWT_SECRET_BYTES bytes " +
						"(256 bits) — got $secretBytes."
			}
		}
		JwtSessionService(config, secret)
	}

	single<NonceManager> {
		val key = requireNotNull(serverConfig.auth?.oauthStateSecret) {
			"auth.oauthStateSecret is required when OIDC providers are configured " +
					"(used to verify the OAuth2 `state` parameter against login CSRF). " +
					"Declare it in server.yml — typically via env-var substitution: " +
					"`oauthStateSecret: \"\${OMNISIGN_OAUTH_NONCE_SECRET}\"`."
		}
		val bytes = key.value.toByteArray(Charsets.UTF_8)
		require(bytes.size >= MIN_NONCE_KEY_BYTES) {
			"auth.oauthStateSecret must be at least $MIN_NONCE_KEY_BYTES bytes (512 bits) — " +
					"got ${bytes.size}."
		}
		StatelessHmacNonceManager(bytes)
	}

	single<Database> {
		val dbFile = sessionsDbFile()
		dbFile.parentFile?.mkdirs()
		Database.connect(
			"jdbc:sqlite:${dbFile.absolutePath}",
			driver = "org.sqlite.JDBC",
			setupConnection = { connection ->
				connection.createStatement().use { it.execute("PRAGMA busy_timeout = $SQLITE_BUSY_TIMEOUT_MS") }
			},
		)
	}

	single<RefreshTokenStore> {
		ExposedRefreshTokenStore(get()).also { it.initSchema() }
	}

	single<PkceVerifierStore> {
		ExposedPkceVerifierStore(get()).also { it.initSchema() }
	}

	single<PkceService> {
		PkceService(get())
	}

	single<LoginRequestStore> {
		ExposedLoginRequestStore(get()).also { it.initSchema() }
	}

	single<HandoffCodeStore> {
		ExposedHandoffCodeStore(get(), get()).also { it.initSchema() }
	}
}

/**
 * Resolve the server's writable trust directory: [ServerConfig.trustStoreDir] when set, otherwise a
 * server-local `trusted-certs` directory. Deliberately not the desktop default location, so a
 * co-located desktop install's trust store is never touched by the server reconcile.
 *
 * @param serverConfig Current server configuration.
 * @return The trust directory path.
 */
private fun serverTrustDir(serverConfig: ServerConfig): Path =
	serverConfig.trustStoreDir?.let { Path.of(it) } ?: Path.of("trusted-certs")

/**
 * Minimum acceptable length, in bytes, of the JWT signing secret.
 *
 * 64 bytes (512 bits) — chosen to meet the strongest HMAC variant the server supports
 * (HS512, the default) per RFC 7518 §3.2, which mandates that the key be at least the
 * size of the hash output. For HS512 that is 64 bytes. The same 64-byte floor is
 * comfortably above what HS256 (≥32) and HS384 (≥48) require — HMAC explicitly permits
 * keys larger than its output size (RFC 7518 §3.2 wording: "or larger") — and is also
 * the optimal key length for HS256, matching SHA-256's internal block size.
 *
 * A unified 64-byte floor across algorithm variants keeps the rule simple and prevents
 * the subtle "HS256-sized secret used with HS512" footgun. Operators generate a
 * compliant secret with `openssl rand -base64 64`.
 */
private const val MIN_JWT_SECRET_BYTES = 64

/**
 * Minimum acceptable length, in bytes, of the OAuth state HMAC key.
 *
 * 64 bytes (512 bits) — chosen for consistency with [MIN_JWT_SECRET_BYTES] and to match
 * SHA-256's internal block size (the default hash used by [StatelessHmacNonceManager]),
 * which is the optimal key length per RFC 2104. RFC 7518 §3.2's "≥ hash output size"
 * minimum for HMAC-SHA-256 would be 32 bytes; 64 bytes goes one step further and matches
 * the block size, giving maximum entropy density before HMAC's internal pad-or-hash step.
 *
 * Operators generate a compliant key with `openssl rand -base64 64`.
 */
private const val MIN_NONCE_KEY_BYTES = 64

/**
 * How long, in milliseconds, a SQLite connection waits for a contended write lock before
 * giving up with `SQLITE_BUSY`.
 *
 * 5 seconds — generous for the only contention this database sees, which is two writes to the
 * same session row landing within milliseconds of each other (concurrent `/auth/refresh` from
 * duplicate tabs, or a double-submitted hand-off code). The winner's transaction is a single
 * indexed delete that commits in well under a millisecond, so the loser almost never waits a
 * measurable fraction of this; the cap only exists to bound a pathological stall rather than
 * to be reached in normal operation.
 */
private const val SQLITE_BUSY_TIMEOUT_MS = 5_000

/**
 * End-to-end timeout for an OIDC discovery or UserInfo HTTP call.
 *
 * 10 seconds — large enough to cover a slow IdP under modest load (TLS handshake +
 * federated discovery can legitimately span several seconds, especially for the
 * Czech academic federation chain via eduID.cz) but tight enough that a
 * pathologically slow or hung IdP cannot pin a request thread indefinitely. Without
 * this cap, a hostile or partitioned IdP would let the request queue grow
 * unboundedly and drain the CIO connection pool, blocking all login attempts.
 */
private const val IDP_REQUEST_TIMEOUT_MS = 10_000L

/**
 * TCP connect timeout for an IdP HTTP call. Lower than the overall request timeout
 * because connect-stage stalls (DNS black-hole, dropped SYNs) are a different
 * failure mode that should surface fast rather than tie up resources.
 */
private const val IDP_CONNECT_TIMEOUT_MS = 5_000L

/**
 * Idle-socket timeout for an IdP HTTP call. Mirrors [IDP_REQUEST_TIMEOUT_MS] because
 * the OIDC requests are single request/response round-trips with no streaming — the
 * request-level cap is the meaningful one; the socket timeout exists for symmetry
 * with the connect timeout and to surface a half-open TCP connection promptly.
 */
private const val IDP_SOCKET_TIMEOUT_MS = 10_000L

