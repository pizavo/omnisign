package cz.pizavo.omnisign.domain.model.config.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Timestamp server (TSA) configuration.
 *
 * The HTTP Basic password is **never** stored here in a persistent form.
 * Two password sources exist:
 *
 * 1. **Persisted** — [credentialKey] is an opaque key used to look up the password
 *    from the OS-native credential store at runtime via
 *    [cz.pizavo.omnisign.domain.service.CredentialStore]. Set via
 *    `config set --timestamp-password`.
 *
 * 2. **Runtime-only** — [runtimePassword] carries a password supplied on the
 *    command line for the current invocation only. It is annotated `@Transient`
 *    so it is never written to disk, and it takes precedence over [credentialKey].
 *
 * @property url TSA endpoint URL (RFC 3161).
 * @property username HTTP Basic auth username; stored in the config file because
 *   it is not considered secret.
 * @property credentialKey Stable lookup key for the OS keychain entry that holds
 *   the HTTP Basic password. Conventionally set to the [username] value when
 *   credentials are configured. `null` means no authentication is required.
 * @property timeout HTTP request timeout in milliseconds.
 * @property runtimePassword In-memory password for the current process only.
 *   Never serialized. Takes precedence over [credentialKey] when non-null.
 */
@Serializable
data class TimestampServerConfig(
    val url: String,
    val username: String? = null,
    val credentialKey: String? = null,
    val timeout: Int = 30000,
    @Transient val runtimePassword: String? = null
)

