package cz.pizavo.omnisign.config

import arrow.core.getOrElse
import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.data.secret.SecretFileReader
import cz.pizavo.omnisign.data.secret.SecretSource
import cz.pizavo.omnisign.domain.model.value.Sensitive
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Operator-supplied secret values resolved from the environment at server startup.
 *
 * Replaces the previous design where secret-bearing strings (`tls.keystorePassword`,
 * `tls.privateKeyPassword`, `auth.session.secret`, `clientSecret` per OIDC provider)
 * were accepted in YAML. YAML-borne secrets cross too many filesystem boundaries
 * (backups, log uploads, support bundles, container images, CI artifacts,
 * screen-shares), and the shipped placeholders (`"changeit"`, `"YOUR_CLIENT_SECRET"`)
 * are a well-documented copy-paste trap. Each secret instead comes from its environment
 * variable, from a file the variable `NAME_FILE` names, or from the systemd credential
 * `NAME` — never from a config file. A secret file is read only where it is kept as a
 * secret should be: a file of its own that no one but its owner (and, for a file of root,
 * one group) can read, mounted for it rather than baked into a container image; see
 * [EnvironmentSecretReader].
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
 * @property signingKeystorePassword Password protecting the file signing keystore
 *   ([OperationsConfig.signingKeystorePath]) — required when that path is set and
 *   [AllowedOperation.SIGN] is enabled, otherwise `null`. Sourced from
 *   `OMNISIGN_SIGNING_KEYSTORE_PASSWORD`.
 * @property oidcClientSecrets OIDC `client_secret` per provider, keyed by
 *   [OidcProviderConfig.name]. One entry per configured OIDC provider; sourced from
 *   `OMNISIGN_OIDC_<NAME>_CLIENT_SECRET` (see [oidcClientSecretEnvVar] for the
 *   derivation rule).
 */
data class ServerSecrets(
	val jwtSecret: Sensitive<String>?,
	val tlsKeystorePassword: Sensitive<String>?,
	val tlsPrivateKeyPassword: Sensitive<String>?,
	val signingKeystorePassword: Sensitive<String>?,
	val oidcClientSecrets: Map<String, Sensitive<String>>,
) {

	companion object {
		/**
		 * Read every required secret named by [serverConfig] and return a [ServerSecrets]
		 * holding the resolved values.
		 *
		 * Rules:
		 * - `OMNISIGN_JWT_SECRET` — required when `auth.enabled: true`.
		 * - `OMNISIGN_TLS_KEYSTORE_PASSWORD` — required when `tls:` is configured.
		 * - `OMNISIGN_TLS_PRIVATE_KEY_PASSWORD` — optional; falls back to the keystore
		 *   password when absent (matching the previous YAML-side default).
		 * - `OMNISIGN_SIGNING_KEYSTORE_PASSWORD` — required when
		 *   `operations.signingKeystorePath` is set and SIGN is enabled.
		 * - `OMNISIGN_OIDC_<NAME>_CLIENT_SECRET` — required for every configured OIDC
		 *   provider, one per provider.
		 *
		 * Each secret `NAME` is read from the variable `NAME`, from the file the variable
		 * `NAME_FILE` names, or from the systemd credential `NAME`, and may be set in only one
		 * of them; a blank variable counts as unset. Each failure surfaces as
		 * [IllegalStateException] naming the variable the operator should set, or saying why
		 * the secret was refused; the log line at the end of a successful resolution lists
		 * where each secret came from (not its value).
		 *
		 * @param serverConfig The root server configuration, post-validation.
		 * @param secretReader Reads a secret from the environment.
		 * @param environment Returns the value of a variable; the process environment by default.
		 * @return Resolved [ServerSecrets] with every required secret populated.
		 * @throws IllegalStateException when a required secret is set nowhere or is empty, or a
		 *   secret is set in more than one place or kept in a file that is not a safe place for it.
		 */
		fun resolveFromEnv(
			serverConfig: ServerConfig,
			secretReader: EnvironmentSecretReader = EnvironmentSecretReader(SecretFileReader()),
			environment: (String) -> String? = System::getenv,
		): ServerSecrets {
			val authEnabled = serverConfig.auth?.enabled == true
			val tlsConfigured = serverConfig.tls != null
			val oidcProviders = serverConfig.auth?.providers
				?.filterIsInstance<OidcProviderConfig>()
				?: emptyList()

			val found = mutableListOf<SecretSource>()

			/**
			 * Read the secret [name] from where the environment sets it, noting that place, or
			 * return `null` when it is set nowhere.
			 */
			fun optional(name: String): Sensitive<String>? {
				val source = secretReader.locate(name, environment).getOrElse { error(it.message) } ?: return null
				found += source
				return secretReader.read(source).getOrElse { error(it.message) }
			}

			/** Read the secret [name], which the configuration requires because [requiredBecause]. */
			fun required(name: String, requiredBecause: String): Sensitive<String> =
				optional(name) ?: error(
					if (environment(name) == null) {
						"$name is required ($requiredBecause) but is not set. Set it before starting the " +
							"server, or set $name${SecretSource.FILE_SUFFIX} to the path of a file that holds it."
					} else {
						"$name is set but empty. Set it to the secret, or unset it and set " +
							"$name${SecretSource.FILE_SUFFIX} to the path of a file that holds the secret."
					},
				)

			val jwtSecret = if (authEnabled) required(JWT_SECRET_ENV, "auth.enabled is true") else null

			val tlsKeystorePassword = if (tlsConfigured) {
				required(TLS_KEYSTORE_PASSWORD_ENV, "tls: block is configured")
			} else null

			val tlsPrivateKeyPassword = if (tlsConfigured) {
				optional(TLS_PRIVATE_KEY_PASSWORD_ENV) ?: tlsKeystorePassword
			} else null

			val signingKeystorePassword = if (
				serverConfig.operations.signingKeystorePath != null &&
				AllowedOperation.SIGN in serverConfig.operations.allowed
			) {
				required(SIGNING_KEYSTORE_PASSWORD_ENV, "operations.signingKeystorePath is set with SIGN enabled")
			} else null

			val oidcClientSecrets = oidcProviders.associate { provider ->
				provider.name to required(
					oidcClientSecretEnvVar(provider.name),
					"OIDC provider '${provider.name}' is configured",
				)
			}

			if (found.isNotEmpty()) {
				logger.info { "Resolved secrets from ${found.joinToString(", ") { it.label }}" }
			}

			return ServerSecrets(
				jwtSecret = jwtSecret,
				tlsKeystorePassword = tlsKeystorePassword,
				tlsPrivateKeyPassword = tlsPrivateKeyPassword,
				signingKeystorePassword = signingKeystorePassword,
				oidcClientSecrets = oidcClientSecrets,
			)
		}

		/** Env var that supplies [jwtSecret]. */
		const val JWT_SECRET_ENV = "OMNISIGN_JWT_SECRET"

		/** Env var that supplies [tlsKeystorePassword]. */
		const val TLS_KEYSTORE_PASSWORD_ENV = "OMNISIGN_TLS_KEYSTORE_PASSWORD"

		/** Env var that supplies [tlsPrivateKeyPassword]. Optional; falls back to [TLS_KEYSTORE_PASSWORD_ENV]. */
		const val TLS_PRIVATE_KEY_PASSWORD_ENV = "OMNISIGN_TLS_PRIVATE_KEY_PASSWORD"

		/** Env var that supplies [signingKeystorePassword]. */
		const val SIGNING_KEYSTORE_PASSWORD_ENV = "OMNISIGN_SIGNING_KEYSTORE_PASSWORD"
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
