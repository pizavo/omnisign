package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.value.Sensitive

/**
 * A timestamp server given for a single operation over the stored
 * [cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig], field by field.
 *
 * A field left `null` keeps the stored server's value when [ResolvedConfig.resolve] merges the
 * two, except that the stored username and password go along only while [url] names the stored
 * server. Unlike the stored configuration, nothing here has a default standing in for a value
 * that was not given, so an explicit value equal to a default still replaces the stored one.
 *
 * @property url The server's URL, or `null` or blank to keep the stored server.
 * @property username The HTTP Basic username, or `null` to keep the stored one.
 * @property runtimePassword The HTTP Basic password for this operation only; never stored.
 * @property timeout The request timeout in milliseconds, or `null` to keep the stored one.
 */
data class TimestampServerOverride(
	val url: String? = null,
	val username: String? = null,
	val runtimePassword: Sensitive<String>? = null,
	val timeout: Int? = null,
)
