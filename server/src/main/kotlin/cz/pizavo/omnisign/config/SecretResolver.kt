package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Operator-supplied secret values resolved from environment variables at server startup.
 *
 * Replaces the previous design where secret-bearing strings (`tls.keystorePassword`,
 * `tls.privateKeyPassword`, `auth.session.secret`, `clientSecret` per OIDC provider)
 * were accepted in YAML. YAML-borne secrets cross too many filesystem boundaries
 * (backups, log uploads, support bundles, container images, CI artifacts,
 * screen-shares), and the shipped placeholders (`"changeit"`, `"YOUR_CLIENT_SECRET"`)
 * are a well-documented copy-paste trap. Env-var-only resolution keeps the secrets in
 * process memory, never on disk in a config-file context.
 *
 * Resolved values are wrapped in [Sensitive] per M-8 so they cannot leak through
 * `toString` (data-class-generated, logger interpolation, `cause.message` echoing).
 *
 * @property jwtSecret HMAC signing secret for [cz.pizavo.omnisign.auth.JwtSessionService]
 *   — required when `auth.enabled: true`, otherwise `null`. Sourced from
 *   `OMNISIGN_JWT_SECRET`.
 * @property tlsKeystorePassword Password protecting the TLS keystore — required when
 *   `tls:` is configured, otherwise `null`. Sourced from `OMNISIGN_TLS_KEYSTORE_PASSWORD`.
 * @property tlsPrivateKeyPassword Password for the TLS private-key entry — falls back to
 *   [tlsKeystorePassword] when its dedicated env var is not set, matching the previous
 *   `TlsConfig.privateKeyPassword = keystorePassword` default. Sourced from
 *   `OMNISIGN_TLS_PRIVATE_KEY_PASSWORD`.
 * @property oidcClientSecrets OIDC `client_secret` per provider, keyed by
 *   [OidcProviderConfig.name]. One entry per configured OIDC provider; sourced from
 *   `OMNISIGN_OIDC_<NAME>_CLIENT_SECRET` (see [oidcClientSecretEnvVar] for the
 *   derivation rule).
 */
data class ServerSecrets(
	val jwtSecret: Sensitive<String>?,
	val tlsKeystorePassword: Sensitive<String>?,
	val tlsPrivateKeyPassword: Sensitive<String>?,
	val oidcClientSecrets: Map<String, Sensitive<String>>,
) {

	companion object {
		/**
		 * Read every required secret env var named by [serverConfig] and return a
		 * [ServerSecrets] holding the resolved values.
		 *
		 * Rules:
		 * - `OMNISIGN_JWT_SECRET` — required when `auth.enabled: true`.
		 * - `OMNISIGN_TLS_KEYSTORE_PASSWORD` — required when `tls:` is configured.
		 * - `OMNISIGN_TLS_PRIVATE_KEY_PASSWORD` — optional; falls back to the keystore
		 *   password when absent (matching the previous YAML-side default).
		 * - `OMNISIGN_OIDC_<NAME>_CLIENT_SECRET` — required for every configured OIDC
		 *   provider, one per provider.
		 *
		 * Each missing-required failure surfaces as [IllegalStateException] naming the
		 * env var the operator should set; the secrets log line at the end of a
		 * successful resolution lists every env var consulted (not its value).
		 *
		 * @param serverConfig The root server configuration, post-validation.
		 * @return Resolved [ServerSecrets] with every required secret populated.
		 * @throws IllegalStateException when a required env var is unset or empty.
		 */
		fun resolveFromEnv(serverConfig: ServerConfig): ServerSecrets {
			val authEnabled = serverConfig.auth?.enabled == true
			val tlsConfigured = serverConfig.tls != null
			val oidcProviders = serverConfig.auth?.providers
				?.filterIsInstance<OidcProviderConfig>()
				?: emptyList()

			val consulted = mutableListOf<String>()

			val jwtSecret = if (authEnabled) {
				consulted += JWT_SECRET_ENV
				resolveRequired(JWT_SECRET_ENV, "auth.enabled is true")
			} else null

			val tlsKeystorePassword = if (tlsConfigured) {
				consulted += TLS_KEYSTORE_PASSWORD_ENV
				resolveRequired(TLS_KEYSTORE_PASSWORD_ENV, "tls: block is configured")
			} else null

			val tlsPrivateKeyPassword = if (tlsConfigured) {
				val explicit = resolveOptional(TLS_PRIVATE_KEY_PASSWORD_ENV)
				if (explicit != null) {
					consulted += TLS_PRIVATE_KEY_PASSWORD_ENV
					explicit
				} else {
					tlsKeystorePassword
				}
			} else null

			val oidcClientSecrets = oidcProviders.associate { provider ->
				val envVar = oidcClientSecretEnvVar(provider.name)
				consulted += envVar
				provider.name to resolveRequired(envVar, "OIDC provider '${provider.name}' is configured")
			}

			if (consulted.isNotEmpty()) {
				logger.info { "Resolved secrets from env: ${consulted.joinToString(", ")}" }
			}

			return ServerSecrets(
				jwtSecret = jwtSecret,
				tlsKeystorePassword = tlsKeystorePassword,
				tlsPrivateKeyPassword = tlsPrivateKeyPassword,
				oidcClientSecrets = oidcClientSecrets,
			)
		}

		/** Env var that supplies [jwtSecret]. */
		const val JWT_SECRET_ENV = "OMNISIGN_JWT_SECRET"

		/** Env var that supplies [tlsKeystorePassword]. */
		const val TLS_KEYSTORE_PASSWORD_ENV = "OMNISIGN_TLS_KEYSTORE_PASSWORD"

		/** Env var that supplies [tlsPrivateKeyPassword]. Optional; falls back to [TLS_KEYSTORE_PASSWORD_ENV]. */
		const val TLS_PRIVATE_KEY_PASSWORD_ENV = "OMNISIGN_TLS_PRIVATE_KEY_PASSWORD"
	}
}

/**
 * Derive the per-provider OIDC `client_secret` env var name from the provider's
 * [OidcProviderConfig.name].
 *
 * The provider name is uppercased and any character outside `[A-Z0-9]` is replaced
 * with `_`. So `name: "google"` → `OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET`;
 * `name: "google-workspace"` → `OMNISIGN_OIDC_GOOGLE_WORKSPACE_CLIENT_SECRET`;
 * `name: "eduid.cz"` → `OMNISIGN_OIDC_EDUID_CZ_CLIENT_SECRET`.
 *
 * The provider's name is already required to be unique across providers (it shapes
 * URL paths), so the derived env-var name is also unique.
 *
 * @param providerName The provider's `name` field from `server.yml`.
 * @return The expected env var name carrying that provider's `client_secret`.
 */
fun oidcClientSecretEnvVar(providerName: String): String =
	"OMNISIGN_OIDC_${providerName.uppercase().replace(Regex("[^A-Z0-9]"), "_")}_CLIENT_SECRET"

/**
 * Read a required env var and wrap the value in [Sensitive].
 *
 * @param envVarName The env var to read.
 * @param requiredBecause Operator-facing reason naming the config state that made this
 *   var required (e.g., `"auth.enabled is true"`); included verbatim in the error.
 * @return The resolved value wrapped in [Sensitive].
 * @throws IllegalStateException when the env var is unset or empty.
 */
private fun resolveRequired(envVarName: String, requiredBecause: String): Sensitive<String> {
	val value = System.getenv(envVarName)
		?: error(
			"$envVarName environment variable is required ($requiredBecause) " +
				"but is not set. Configure it before starting the server — e.g. " +
				"`export $envVarName=...`.",
		)
	require(value.isNotBlank()) {
		"$envVarName environment variable is set but empty. Either unset it or provide a value."
	}
	return value.sensitive()
}

/**
 * Read an optional env var; return `null` when unset or empty.
 *
 * @param envVarName The env var to read.
 * @return The resolved [Sensitive] value, or `null` when the var is unset or blank.
 */
private fun resolveOptional(envVarName: String): Sensitive<String>? =
	System.getenv(envVarName)?.takeIf { it.isNotBlank() }?.sensitive()
