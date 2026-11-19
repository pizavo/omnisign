package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.ParsedProxyConfig
import cz.pizavo.omnisign.config.TrustedProxy
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import java.net.Inet4Address
import java.net.Inet6Address
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
 * misconfigured deployment) spoof the address the rate limiter keys them on, the one
 * reader of the call's origin. The TCP-peer check is enforced application-side in addition to —
 * not instead of — OS-level network restrictions.
 *
 * Only `X-Forwarded-For` is honored, and the client's address is the one
 * [forwardedClientAddress] picks out of it: the rightmost entry that is not a trusted
 * proxy's address. A proxy that appends to the header, as most load balancers do, leaves
 * the entries a client wrote itself on the left, where they never count. The address is
 * set in its canonical form, without a port, so that the rate limiter keys every spelling
 * of one address to one bucket. When the header is missing, or [forwardedClientAddress]
 * finds no address in it, the TCP peer stays the client.
 *
 * `X-Forwarded-Proto` and `X-Forwarded-Host` are not consumed because no current request
 * handler depends on `call.request.origin.scheme` or `.host`; if a future feature does,
 * the per-header allowlist below should be extended with the same trusted-peer gate
 * applied.
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

		val headerLines = call.request.headers.getAll(HttpHeaders.XForwardedFor) ?: return@intercept
		val client = forwardedClientAddress(headerLines, trusted)
		if (client == null) {
			logger.debug {
				"Ignoring X-Forwarded-For from trusted proxy '$peerText': it names no client address"
			}
			return@intercept
		}

		val origin = call.mutableOriginConnectionPoint
		origin.remoteHost = client.hostAddress
		origin.remoteAddress = client.hostAddress
	}
}

/**
 * Pick the client's address out of the `X-Forwarded-For` header of a request that a trusted
 * proxy forwarded.
 *
 * The entries of all [headerLines], in the order they arrived, form one list, since a proxy
 * may add a header line of its own rather than append to the last one; empty entries are
 * skipped. The list is read from the right: each entry that is the address of one of
 * [trusted] is a proxy on the way and is skipped, and the first that is not is the client's.
 * The entries to its left are never read, because the client may have written them itself.
 * When every entry is a trusted proxy's, the leftmost is the client's, as a request from
 * inside the trusted network looks.
 *
 * @param headerLines The values of every `X-Forwarded-For` header line of the request.
 * @param trusted The trusted proxies from `proxy.trusted`.
 * @return The client's address, or `null` when the header lists no entry, or when an entry
 *   read on the way is not an IP address ([parseForwardedEntry]): the header is then not
 *   believed, and the TCP peer stays the client.
 */
internal fun forwardedClientAddress(headerLines: List<String>, trusted: List<TrustedProxy>): InetAddress? {
	val entries = headerLines.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
	var client: InetAddress? = null
	for (entry in entries.asReversed()) {
		val address = parseForwardedEntry(entry) ?: return null
		client = address
		if (trusted.none { it.matches(address) }) break
	}
	return client
}

/**
 * Parse one `X-Forwarded-For` entry as an IP address, never invoking DNS.
 *
 * Accepted forms are an IPv4 address in dotted-quad form, an IPv6 address, bare or in
 * brackets, and either with a port after it (`203.0.113.7:4711`, `[2001:db8::7]:443`), as
 * some load balancers write it; the port is dropped. Anything else, such as a hostname,
 * `unknown`, or an IPv6 address with a zone, is not an address.
 *
 * @param entry One comma-separated entry of the header, trimmed.
 * @return The address, or `null` when [entry] is not one of the accepted forms.
 */
internal fun parseForwardedEntry(entry: String): InetAddress? {
	val host = when {
		entry.startsWith('[') -> BRACKETED_IPV6_ENTRY.matchEntire(entry)?.groupValues?.get(1) ?: return null
		IPV4_ENTRY_WITH_PORT.matches(entry) -> entry.substringBefore(':')
		else -> entry
	}
	return try {
		when {
			IPV4_LITERAL.matches(host) -> Inet4Address.ofLiteral(host)
			':' in host && '%' !in host -> Inet6Address.ofLiteral(host)
			else -> null
		}
	} catch (_: IllegalArgumentException) {
		null
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

private val IPV4_ENTRY_WITH_PORT = Regex("""^(\d{1,3}\.){3}\d{1,3}:\d{1,5}$""")

private val BRACKETED_IPV6_ENTRY = Regex("""^\[([0-9A-Fa-f:.]+)](?::\d{1,5})?$""")
