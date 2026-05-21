package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.auth.ExposedPkceVerifierStore
import cz.pizavo.omnisign.auth.ExposedRefreshTokenStore
import cz.pizavo.omnisign.auth.IdTokenVerifier
import cz.pizavo.omnisign.auth.JwtSessionService
import cz.pizavo.omnisign.auth.OidcDiscoveryService
import cz.pizavo.omnisign.auth.OidcUserInfoService
import cz.pizavo.omnisign.auth.PkceService
import cz.pizavo.omnisign.auth.PkceVerifierStore
import cz.pizavo.omnisign.auth.RefreshTokenStore
import cz.pizavo.omnisign.auth.ServerPasswordCallback
import cz.pizavo.omnisign.auth.sessionsDbFile
import cz.pizavo.omnisign.config.AllowedOperation
import cz.pizavo.omnisign.config.ServerConfig
import cz.pizavo.omnisign.config.ServerConfigLoader
import cz.pizavo.omnisign.config.SessionConfig
import cz.pizavo.omnisign.platform.PasswordCallback
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.NonceManager
import io.ktor.util.StatelessHmacNonceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext

/**
 * Server-specific Koin module.
 *
 * Provides:
 * - [ServerConfig] singleton from the preloaded configuration.
 * - [ServerConfigLoader] singleton.
 * - [PasswordCallback] that always returns `null` (server cannot prompt interactively).
 * - IO [CoroutineContext] for blocking work.
 * - [HttpClient] (CIO engine) with JSON content-negotiation for OIDC discovery and
 *   user-info requests.
 * - [JwtSessionService] for issuing and verifying session tokens. The signing secret is
 *   read from `auth.session.secret` (typically declared in `server.yml` via env-var
 *   substitution: `secret: "${OMNISIGN_JWT_SECRET}"`). When `auth.enabled` is `true` the
 *   field is required and must be at least [MIN_JWT_SECRET_BYTES] bytes — startup fails
 *   otherwise. When `auth.enabled` is `false` the field may be omitted; [JwtSessionService]
 *   becomes inert (`verify` returns `null` for every token, `issue` throws if called).
 * - [NonceManager] for OAuth2 `state`-parameter CSRF protection on the authorization-code
 *   callback. Implemented as [StatelessHmacNonceManager]; the HMAC key is read from
 *   `auth.oauthStateSecret` (typically declared via env-var substitution:
 *   `oauthStateSecret: "${OMNISIGN_OAUTH_NONCE_SECRET}"`). The binding is lazy and only
 *   resolves when an OIDC provider's `oauth { … }` block actually needs it; deployments
 *   without OIDC providers never hit the resolution and never need to configure the field.
 *   When the binding does resolve the field is required and must be at least
 *   [MIN_NONCE_KEY_BYTES] bytes — startup fails otherwise.
 * - [Database] singleton: SQLite connection rooted at [sessionsDbFile]. Created lazily
 *   on first injection; the parent directory is created if it does not exist.
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
 * - [OidcDiscoveryService] and [OidcUserInfoService] for the OIDC authorization-code flow.
 * - [IdTokenVerifier] for cryptographic verification of the OIDC `id_token` returned by
 *   the IdP alongside the access token on the authorization-code callback.
 *
 * @param serverConfig Preloaded server configuration.
 */
fun serverModule(serverConfig: ServerConfig) = module {
	single<ServerConfig> { serverConfig }
	single<ServerConfigLoader> { ServerConfigLoader() }
	single<PasswordCallback> { ServerPasswordCallback() }
	single<CoroutineContext> { Dispatchers.IO }

	if (AllowedOperation.SIGN in serverConfig.allowedOperations) {
		single { MutableStateFlow(false) }
	}

	single<HttpClient> {
		HttpClient(CIO) {
			install(ContentNegotiation) {
				json(Json { ignoreUnknownKeys = true })
			}
		}
	}

	single<OidcDiscoveryService> { OidcDiscoveryService(get()) }
	single<OidcUserInfoService> { OidcUserInfoService(get()) }
	single<IdTokenVerifier> { IdTokenVerifier(get()) }

	single<JwtSessionService> {
		val config = serverConfig.auth?.session ?: SessionConfig()
		val authEnabled = serverConfig.auth?.enabled == true
		val secret = config.secret
		if (secret == null) {
			require(!authEnabled) {
				"auth.session.secret is required when auth.enabled is true. " +
						"Declare it in server.yml — typically via env-var substitution: " +
						"`secret: \"\${OMNISIGN_JWT_SECRET}\"`."
			}
		} else {
			val secretBytes = secret.value.toByteArray(Charsets.UTF_8).size
			require(secretBytes >= MIN_JWT_SECRET_BYTES) {
				"auth.session.secret must be at least $MIN_JWT_SECRET_BYTES bytes (256 bits) — " +
						"got $secretBytes."
			}
		}
		JwtSessionService(config)
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
		Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
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
}

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

