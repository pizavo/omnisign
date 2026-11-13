package cz.pizavo.omnisign.data.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.apache.hc.client5.http.DnsResolver
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Verifies that [PublicAddressDnsResolver] tells public addresses from internal ones, and that a host
 * resolving only to internal addresses cannot be connected to.
 *
 * Every address is an IP literal, which [InetAddress.getByName] parses without a DNS lookup, so the
 * tests need no network. Where a test needs a particular DNS answer, the resolver's delegate gives it.
 */
class PublicAddressDnsResolverTest : FunSpec({

	val internalAddresses = listOf(
		"0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255",
		"127.0.0.1", "127.255.255.254", "169.254.169.254", "172.16.0.1", "172.31.255.255",
		"192.0.0.8", "192.0.2.1", "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.7",
		"203.0.113.9", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
		"168.63.129.16",
		"::", "::1", "::127.0.0.1", "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:0:127.0.0.1",
		"::ffff:0:a9fe:a9fe", "64:ff9b::a9fe:a9fe", "2002:7f00:1::1", "2002:a9fe:a9fe::", "2002:a00:1:5::1",
		"2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001:1::1", "2001:2::1", "2001:1ff:ffff::1", "64:ff9b:1::1",
		"100::1", "2001:db8::1", "fc00::1", "fd12:3456:789a::1", "fe80::1", "fec0::1", "ff02::1",
	)
	val publicAddresses = listOf(
		"1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.0", "168.63.129.15", "168.63.129.17",
		"172.15.255.255", "172.32.0.0", "192.0.1.1", "192.169.0.1", "198.17.255.255", "198.20.0.0",
		"223.255.255.255", "2001:200::1", "2001:4860:4860::8888", "2606:4700:4700::1111", "64:ff9b::808:808",
		"::ffff:0:808:808", "2002:808:808::1",
	)

	internalAddresses.forEach { literal ->
		test("$literal is not public") {
			PublicAddressDnsResolver.isPublic(InetAddress.getByName(literal)).shouldBeFalse()
		}
	}

	publicAddresses.forEach { literal ->
		test("$literal is public") {
			PublicAddressDnsResolver.isPublic(InetAddress.getByName(literal)).shouldBeTrue()
		}
	}

	test("a URL naming an internal address cannot be resolved") {
		val error = shouldThrow<UnknownHostException> { PublicAddressDnsResolver().resolve("169.254.169.254") }
		error.message shouldContain "169.254.169.254"
	}

	test("localhost cannot be resolved") {
		shouldThrow<UnknownHostException> { PublicAddressDnsResolver().resolve("localhost") }
	}

	test("a host with public and internal addresses keeps only the public ones") {
		val resolver = PublicAddressDnsResolver(
			answering("10.0.0.5", "93.184.215.14", "fd00::5", "2606:2800:21f:cb07:6820:80da:af6b:8b2c")
		)
		resolver.resolve("mixed.example").map { it.hostAddress } shouldContainExactly
			listOf("93.184.215.14", "2606:2800:21f:cb07:6820:80da:af6b:8b2c")
	}

	test("the host-and-port lookup HttpClient connects with fails as well, instead of leaving the host unresolved") {
		shouldThrow<UnknownHostException> { PublicAddressDnsResolver().resolve("127.0.0.1", 80) }
	}
}) {
	companion object {

		/** A [DnsResolver] that answers every host with the addresses in [literals]. */
		fun answering(vararg literals: String): DnsResolver = object : DnsResolver {
			override fun resolve(host: String): Array<InetAddress> =
				literals.map { InetAddress.getByName(it) }.toTypedArray()

			override fun resolveCanonicalHostname(host: String): String = host
		}
	}
}
