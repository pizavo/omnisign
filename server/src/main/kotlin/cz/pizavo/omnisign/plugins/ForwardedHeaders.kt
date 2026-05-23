package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.ParsedProxyConfig
import cz.pizavo.omnisign.config.TrustedProxy
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import java.net.InetAddress

private val logger = KotlinLogging.logger {}

/**
 * Install the trusted-proxy-aware `X-Forwarded-*` plugin when reverse-proxy mode is
 * enabled in [parsed].
 *
 * Unlike Ktor's built-in [io.ktor.server.plugins.forwardedheaders.XForwardedHeaders],
 * this plugin gates the rewrite on the TCP peer's address: `X-Forwarded-*` headers are
 * honored only when the connection's TCP source IP matches one of the parsed
 * [TrustedProxy] entries configured under `proxy.trusted`. Headers arriving from any
 * other peer are ignored, even if they exist on the wire.
 *
 * The narrow surface is the M-7 fix: the previous flat `proxyMode: true` install of
 * Ktor's `XForwardedHeaders` accepted forwarded headers from any TCP peer, which let an
 * attacker reaching the Ktor port directly (or sharing the same LAN as a
 * misconfigured deployment) spoof their effective IP for rate-limiting and audit
 * logging purposes. The TCP-peer check is enforced application-side in addition to —
 * not instead of — OS-level network restrictions.
 *
 * Only `X-Forwarded-For` is honored. `X-Forwarded-Proto` and `X-Forwarded-Host` are
 * not consumed because no current request handler depends on `call.request.origin.scheme`
 * or `.host`; if a future feature does, the per-header allowlist below should be
 * extended with the same trusted-peer gate applied.
 *
 * @param parsed The validator's parsed view of `proxy:` config — including whether
 *   the plugin should install at all and the list of trusted proxies to check against.
 */
fun Application.configureForwardedHeaders(parsed: ParsedProxyConfig) {
	if (!parsed.enabled) return
	val trusted = parsed.trustedProxies

	intercept(ApplicationCallPipeline.Setup) {
		val peerText = call.request.local.remoteAddress
		val peer = parseInetAddressOrNull(peerText)
		if (peer == null || trusted.none { it.matches(peer) }) {
			logger.trace {
				"Ignoring X-Forwarded-* headers from untrusted TCP peer '$peerText'"
			}
			return@intercept
		}

		val effectiveClient = call.request.headers[HttpHeaders.XForwardedFor]
			?.split(',')
			?.firstOrNull()
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return@intercept

		val origin = call.mutableOriginConnectionPoint
		origin.remoteHost = effectiveClient
		origin.remoteAddress = effectiveClient
	}
}

/**
 * Parse [text] as an IP literal without ever invoking DNS.
 *
 * `call.request.local.remoteAddress` is documented as the host string of the TCP peer.
 * Netty surfaces this as the dotted-quad or `[ipv6]:port`-style address rather than a
 * hostname, so [InetAddress.getByName] short-circuits on the literal without a resolver
 * call. The shape guard (`looksIpv4 || looksIpv6`) returns `null` for anything else
 * rather than risking a DNS lookup on a surprising input.
 */
private fun parseInetAddressOrNull(text: String): InetAddress? {
	val looksIpv4 = IPV4_LITERAL.matches(text)
	val looksIpv6 = ':' in text
	if (!looksIpv4 && !looksIpv6) return null
	return try {
		InetAddress.getByName(text)
	} catch (_: Exception) {
		null
	}
}

private val IPV4_LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
