package cz.pizavo.omnisign.config

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * A parsed entry from [ProxyConfig.trusted].
 *
 * Two forms are supported:
 * - [SingleIp] — exact-match against one IPv4 or IPv6 address.
 * - [CidrRange] — prefix-match against an IPv4 or IPv6 CIDR range.
 *
 * Parsing happens once at server startup via [parseTrustedProxy]; per-request matching
 * via [matches] is a byte-array AND-and-compare so the hot path stays trivial. Hostnames
 * are deliberately not accepted — see [parseTrustedProxy] for the rationale.
 */
sealed interface TrustedProxy {

	/**
	 * Returns `true` when [address] is covered by this entry. The comparison operates on
	 * the raw byte representation returned by [InetAddress.getAddress] so it is agnostic
	 * to the textual form the address was constructed from.
	 *
	 * @param address The TCP peer's address, taken from
	 *   `call.request.local.remoteAddress` after parsing.
	 */
	fun matches(address: InetAddress): Boolean

	/**
	 * Exact-match entry — accepts only the single configured [address].
	 *
	 * Used when an operator lists a specific proxy IP such as `127.0.0.1` or `::1`.
	 *
	 * @property address The configured address, parsed from the YAML entry.
	 */
	data class SingleIp(val address: InetAddress) : TrustedProxy {
		override fun matches(address: InetAddress): Boolean =
			this.address.address.contentEquals(address.address)
	}

	/**
	 * CIDR-range entry — accepts any address whose first [prefixLength] bits equal the
	 * configured [prefix].
	 *
	 * The bit mask is precomputed at parse time so per-request matching is a single
	 * byte-array AND-and-compare with no allocation.
	 *
	 * @property prefix The network prefix's address bytes, with the host portion already
	 *   zeroed out so [matches] can compare byte-for-byte without re-masking the prefix.
	 * @property prefixLength Number of significant bits in the prefix.
	 *   `0..32` for IPv4, `0..128` for IPv6.
	 */
	data class CidrRange(val prefix: ByteArray, val prefixLength: Int) : TrustedProxy {

		override fun matches(address: InetAddress): Boolean {
			val bytes = address.address
			if (bytes.size != prefix.size) return false
			val fullBytes = prefixLength / 8
			val remainingBits = prefixLength % 8
			for (i in 0 until fullBytes) {
				if (bytes[i] != prefix[i]) return false
			}
			if (remainingBits == 0) return true
			val mask = (0xFF shl (8 - remainingBits)) and 0xFF
			return (bytes[fullBytes].toInt() and mask) == (prefix[fullBytes].toInt() and mask)
		}

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is CidrRange) return false
			return prefixLength == other.prefixLength && prefix.contentEquals(other.prefix)
		}

		override fun hashCode(): Int = 31 * prefixLength + prefix.contentHashCode()
	}
}

/**
 * Parse a single [ProxyConfig.trusted] entry into a [TrustedProxy], or return `null` when
 * the input is not a valid IP address or CIDR range.
 *
 * Accepted forms:
 * - `"127.0.0.1"` / `"::1"` — single IPv4 / IPv6 address.
 * - `"10.0.0.0/8"` / `"fc00::/7"` — IPv4 / IPv6 CIDR range. The prefix length must be
 *   `0..32` for IPv4 and `0..128` for IPv6.
 *
 * Hostnames (`localhost`, `proxy.example.com`) are deliberately rejected — accepting them
 * would introduce a runtime dependency on the OS resolver and create a DNS-poisoning vector;
 * `localhost` also resolves inconsistently across platforms (`127.0.0.1` on some, `::1` on
 * others). Same convention as nginx `real_ip_from`, Caddy `trusted_proxies`, Apache
 * `RemoteIPInternalProxy`.
 *
 * @param entry The raw YAML string.
 * @return The parsed [TrustedProxy], or `null` when [entry] is not a recognized IP literal
 *   or CIDR range.
 */
fun parseTrustedProxy(entry: String): TrustedProxy? {
	val trimmed = entry.trim()
	if (trimmed.isEmpty()) return null

	val slashIndex = trimmed.indexOf('/')
	if (slashIndex < 0) {
		val address = parseIpLiteral(trimmed) ?: return null
		return TrustedProxy.SingleIp(address)
	}

	val addressPart = trimmed.substring(0, slashIndex)
	val prefixPart = trimmed.substring(slashIndex + 1)
	val address = parseIpLiteral(addressPart) ?: return null
	val prefixLength = prefixPart.toIntOrNull() ?: return null

	val maxPrefix = when (address) {
		is Inet4Address -> 32
		is Inet6Address -> 128
		else -> return null
	}
	if (prefixLength !in 0..maxPrefix) return null

	val bytes = address.address
	maskInPlace(bytes, prefixLength)
	return TrustedProxy.CidrRange(prefix = bytes, prefixLength = prefixLength)
}

/**
 * Parse an IP literal without ever invoking DNS resolution.
 *
 * A leading regex screen ensures only IPv4/IPv6-shaped inputs reach
 * [InetAddress.getByName], which short-circuits IP literals to a direct byte parse but
 * would perform a DNS lookup on plausible hostnames. Anything that does not match either
 * shape returns `null` so the caller can reject the entry as malformed.
 */
private fun parseIpLiteral(text: String): InetAddress? {
	val looksIpv4 = IPV4_LITERAL.matches(text)
	val looksIpv6 = ':' in text
	if (!looksIpv4 && !looksIpv6) return null
	return try {
		InetAddress.getByName(text)
	} catch (_: Exception) {
		null
	}
}

/**
 * Zero the host portion of [bytes] in place, leaving the leading [prefixLength] bits as
 * the network prefix. Used so [TrustedProxy.CidrRange.matches] can compare byte-for-byte
 * against a request address that has already been canonicalised the same way.
 */
private fun maskInPlace(bytes: ByteArray, prefixLength: Int) {
	val fullBytes = prefixLength / 8
	val remainingBits = prefixLength % 8
	if (remainingBits != 0 && fullBytes < bytes.size) {
		val mask = (0xFF shl (8 - remainingBits)) and 0xFF
		bytes[fullBytes] = (bytes[fullBytes].toInt() and mask).toByte()
	}
	for (i in (fullBytes + (if (remainingBits == 0) 0 else 1)) until bytes.size) {
		bytes[i] = 0
	}
}

private val IPV4_LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
