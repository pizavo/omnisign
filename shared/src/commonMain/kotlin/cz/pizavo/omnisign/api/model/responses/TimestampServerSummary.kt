package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import kotlinx.serialization.Serializable

/**
 * Sanitized representation of [TimestampServerConfig] safe for API responses.
 *
 * The raw [TimestampServerConfig.credentialKey] (OS keychain lookup key) and any
 * in-memory password are intentionally omitted. [hasCredentials] indicates whether
 * credentials are configured so the client can reflect this in the UI without
 * exposing the actual secret material.
 *
 * @property url TSA endpoint URL (RFC 3161).
 * @property username HTTP Basic auth username; not considered secret.
 * @property hasCredentials `true` when a credential key or runtime password is present.
 * @property timeout HTTP request timeout in milliseconds.
 */
@Serializable
data class TimestampServerSummary(
	val url: String,
	val username: String?,
	val hasCredentials: Boolean,
	val timeout: Int,
)

/**
 * Map a [TimestampServerConfig] to a [TimestampServerSummary] by stripping sensitive fields.
 */
fun TimestampServerConfig.toSummary() = TimestampServerSummary(
	url = url,
	username = username,
	hasCredentials = credentialKey != null || runtimePassword != null,
	timeout = timeout,
)

/**
 * Reverse-map a sanitized [TimestampServerSummary] back into a [TimestampServerConfig]
 * by leaving `credentialKey` and `runtimePassword` `null`.
 *
 * Web clients use this to fold the server-supplied summary into a domain config they
 * never sign with — the timestamp credentials live on the server side and are never
 * sent over the wire, so the round-tripped value can drive read-only UI but is not
 * suitable for issuing TSA requests directly.
 */
fun TimestampServerSummary.toConfig() = TimestampServerConfig(
	url = url,
	username = username,
	credentialKey = null,
	timeout = timeout,
)
