package cz.pizavo.omnisign.data.repository

import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.SystemDefaultDnsResolver
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * [DnsResolver] that resolves through [delegate] and keeps only the public addresses, so the HTTP
 * client it is installed in never connects to a loopback, private-network, link-local or other
 * non-public address.
 *
 * Apache HttpClient asks its resolver for every connection it opens: for a host name and for a URL
 * that names an IP address, and again for each redirect it follows. The check therefore applies to
 * the address actually connected to, and a DNS answer that changes between two lookups cannot slip
 * an internal address past it.
 *
 * A host that resolves only to non-public addresses fails with [UnknownHostException]. A host with
 * both kinds keeps only its public addresses.
 *
 * @property delegate The resolver whose answers are filtered.
 */
class PublicAddressDnsResolver(
	private val delegate: DnsResolver = SystemDefaultDnsResolver.INSTANCE,
) : DnsResolver {

	/**
	 * Resolve [host] through [delegate] and return only its public addresses.
	 *
	 * @throws UnknownHostException When [host] cannot be resolved or has no public address.
	 */
	override fun resolve(host: String): Array<InetAddress> {
		val publicAddresses = (delegate.resolve(host) ?: emptyArray()).filter { isPublic(it) }
		if (publicAddresses.isEmpty()) {
			throw UnknownHostException("$host has no public address, and downloads from internal addresses are not allowed")
		}
		return publicAddresses.toTypedArray()
	}

	/**
	 * Resolve the canonical name of [host] through [delegate]. Only authentication schemes ask for
	 * it, and nothing connects to the name it returns without going through [resolve].
	 */
	override fun resolveCanonicalHostname(host: String): String = delegate.resolveCanonicalHostname(host)

	companion object {

		/** Offset of the IPv4 address that an IPv4-mapped, IPv4-compatible or NAT64 IPv6 address carries. */
		private const val IPV4_EMBEDDED_OFFSET = 12

		/**
		 * The IPv4 ranges that are not publicly routable, from the IANA special-purpose address
		 * registry: "this network" (`0.0.0.0/8`), the RFC 1918 private networks, carrier-grade NAT
		 * (`100.64.0.0/10`), loopback, link-local (which holds the cloud metadata endpoint
		 * `169.254.169.254`), the IETF protocol and documentation blocks, the benchmarking block,
		 * multicast, and the reserved block up to and including the broadcast address.
		 */
		private val NON_PUBLIC_IPV4_RANGES = listOf(
			"0.0.0.0/8",
			"10.0.0.0/8",
			"100.64.0.0/10",
			"127.0.0.0/8",
			"169.254.0.0/16",
			"172.16.0.0/12",
			"192.0.0.0/24",
			"192.0.2.0/24",
			"192.168.0.0/16",
			"198.18.0.0/15",
			"198.51.100.0/24",
			"203.0.113.0/24",
			"224.0.0.0/4",
			"240.0.0.0/4",
		).map(AddressRange::parse)

		/**
		 * The IPv6 ranges that are not publicly routable: the discard-only block, the local-use
		 * NAT64 prefix, the documentation prefix, unique-local addresses (`fc00::/7`), link-local and
		 * the deprecated site-local prefix, and multicast. Loopback and the unspecified address are
		 * caught as IPv4-compatible forms of `0.0.0.1` and `0.0.0.0`; see [embeddedIpv4].
		 */
		private val NON_PUBLIC_IPV6_RANGES = listOf(
			"100::/64",
			"64:ff9b:1::/48",
			"2001:db8::/32",
			"fc00::/7",
			"fe80::/10",
			"fec0::/10",
			"ff00::/8",
		).map(AddressRange::parse)

		/** First twelve bytes of an IPv4-mapped IPv6 address, `::ffff:a.b.c.d`. */
		private val IPV4_MAPPED_PREFIX = ByteArray(IPV4_EMBEDDED_OFFSET - 2) + byteArrayOf(-1, -1)

		/** First twelve bytes of the well-known NAT64 prefix `64:ff9b::/96`, which carries an IPv4 address. */
		private val NAT64_PREFIX = InetAddress.getByName("64:ff9b::").address.copyOf(IPV4_EMBEDDED_OFFSET)

		/**
		 * Whether [address] is a public unicast address that a download may connect to.
		 *
		 * An IPv6 address that carries an IPv4 address (IPv4-mapped, IPv4-compatible or behind the
		 * well-known NAT64 prefix) is judged by the IPv4 address it reaches.
		 */
		fun isPublic(address: InetAddress): Boolean {
			embeddedIpv4(address)?.let { return isPublic(it) }
			val ranges = if (address is Inet4Address) NON_PUBLIC_IPV4_RANGES else NON_PUBLIC_IPV6_RANGES
			return ranges.none { it.contains(address) }
		}

		/**
		 * The IPv4 address that the IPv6 [address] stands for, or `null` when it is not one of the
		 * forms that carry one in their last four bytes. An address whose first twelve bytes are zero
		 * counts as IPv4-compatible, which also puts `::1` and `::` in `0.0.0.0/8`.
		 */
		private fun embeddedIpv4(address: InetAddress): Inet4Address? {
			if (address !is Inet6Address) return null
			val bytes = address.address
			val prefix = bytes.copyOf(IPV4_EMBEDDED_OFFSET)
			val carriesIpv4 = prefix.all { it == 0.toByte() } ||
				prefix.contentEquals(IPV4_MAPPED_PREFIX) ||
				prefix.contentEquals(NAT64_PREFIX)
			if (!carriesIpv4) return null
			return InetAddress.getByAddress(bytes.copyOfRange(IPV4_EMBEDDED_OFFSET, bytes.size)) as Inet4Address
		}
	}

	/**
	 * A CIDR block: the addresses of [network]'s family whose first [prefixLength] bits equal its own.
	 */
	private class AddressRange(private val network: ByteArray, private val prefixLength: Int) {

		/** Whether [address] is of the same family as this block and inside it. */
		fun contains(address: InetAddress): Boolean {
			val bytes = address.address
			if (bytes.size != network.size) return false
			val wholeBytes = prefixLength / Byte.SIZE_BITS
			for (index in 0 until wholeBytes) {
				if (bytes[index] != network[index]) return false
			}
			val remainingBits = prefixLength % Byte.SIZE_BITS
			if (remainingBits == 0) return true
			val mask = (BYTE_MASK shl (Byte.SIZE_BITS - remainingBits)) and BYTE_MASK
			return (bytes[wholeBytes].toInt() and mask) == (network[wholeBytes].toInt() and mask)
		}

		companion object {

			/** All eight bits of a byte, for masking a byte widened to an [Int]. */
			private const val BYTE_MASK = 0xff

			/** Parse a block written as `address/prefixLength`, the address being an IP literal. */
			fun parse(cidr: String): AddressRange {
				val (literal, length) = cidr.split('/')
				return AddressRange(InetAddress.getByName(literal).address, length.toInt())
			}
		}
	}
}
