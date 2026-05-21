package cz.pizavo.omnisign.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetAddress

/**
 * Unit tests for [parseTrustedProxy] and the [TrustedProxy.matches] hot path.
 *
 * Per-request matching is the security-critical bit (every request consults this), so
 * the parser must (a) accept exactly the forms documented in [ProxyConfig], and
 * (b) refuse hostnames so DNS-poisoning is not a vector at startup. The matcher must
 * agree with standard CIDR semantics on both address families.
 */
class TrustedProxyTest : FunSpec({

    test("parses a single IPv4 literal") {
        val parsed = parseTrustedProxy("127.0.0.1")
        parsed.shouldBeInstanceOf<TrustedProxy.SingleIp>()
        parsed.matches(InetAddress.getByName("127.0.0.1")) shouldBe true
        parsed.matches(InetAddress.getByName("127.0.0.2")) shouldBe false
    }

    test("parses a single IPv6 literal") {
        val parsed = parseTrustedProxy("::1")
        parsed.shouldBeInstanceOf<TrustedProxy.SingleIp>()
        parsed.matches(InetAddress.getByName("::1")) shouldBe true
        parsed.matches(InetAddress.getByName("::2")) shouldBe false
    }

    test("parses IPv4 CIDR /8 and matches every host inside the prefix") {
        val parsed = parseTrustedProxy("10.0.0.0/8")
        parsed.shouldBeInstanceOf<TrustedProxy.CidrRange>()
        parsed.matches(InetAddress.getByName("10.0.0.1")) shouldBe true
        parsed.matches(InetAddress.getByName("10.255.255.255")) shouldBe true
        parsed.matches(InetAddress.getByName("11.0.0.0")) shouldBe false
        parsed.matches(InetAddress.getByName("9.255.255.255")) shouldBe false
    }

    test("parses IPv4 CIDR /24 and respects boundary precisely") {
        val parsed = parseTrustedProxy("192.168.1.0/24")
        parsed.shouldBeInstanceOf<TrustedProxy.CidrRange>()
        parsed.matches(InetAddress.getByName("192.168.1.0")) shouldBe true
        parsed.matches(InetAddress.getByName("192.168.1.255")) shouldBe true
        parsed.matches(InetAddress.getByName("192.168.2.0")) shouldBe false
        parsed.matches(InetAddress.getByName("192.168.0.255")) shouldBe false
    }

    test("parses IPv4 CIDR with a sub-byte prefix length") {
        val parsed = parseTrustedProxy("10.128.0.0/9")
        parsed.shouldNotBeNull()
        parsed.matches(InetAddress.getByName("10.128.0.0")) shouldBe true
        parsed.matches(InetAddress.getByName("10.255.255.255")) shouldBe true
        parsed.matches(InetAddress.getByName("10.127.255.255")) shouldBe false
        parsed.matches(InetAddress.getByName("11.0.0.0")) shouldBe false
    }

    test("parses IPv6 CIDR /7 (unique local address range)") {
        val parsed = parseTrustedProxy("fc00::/7")
        parsed.shouldBeInstanceOf<TrustedProxy.CidrRange>()
        parsed.matches(InetAddress.getByName("fc00::1")) shouldBe true
        parsed.matches(InetAddress.getByName("fd00::1")) shouldBe true
        parsed.matches(InetAddress.getByName("fe00::1")) shouldBe false
        parsed.matches(InetAddress.getByName("fb00::1")) shouldBe false
    }

    test("prefix /0 matches everything in the same address family") {
        val parsedV4 = parseTrustedProxy("0.0.0.0/0")
        parsedV4.shouldNotBeNull()
        parsedV4.matches(InetAddress.getByName("1.2.3.4")) shouldBe true
        parsedV4.matches(InetAddress.getByName("255.255.255.255")) shouldBe true
        parsedV4.matches(InetAddress.getByName("::1")) shouldBe false
    }

    test("prefix /32 (IPv4) or /128 (IPv6) is equivalent to a single-IP match") {
        val parsedV4 = parseTrustedProxy("203.0.113.5/32")
        parsedV4.shouldNotBeNull()
        parsedV4.matches(InetAddress.getByName("203.0.113.5")) shouldBe true
        parsedV4.matches(InetAddress.getByName("203.0.113.6")) shouldBe false

        val parsedV6 = parseTrustedProxy("2001:db8::1/128")
        parsedV6.shouldNotBeNull()
        parsedV6.matches(InetAddress.getByName("2001:db8::1")) shouldBe true
        parsedV6.matches(InetAddress.getByName("2001:db8::2")) shouldBe false
    }

    test("matches refuses to cross address families") {
        val parsedV4 = parseTrustedProxy("127.0.0.0/8")
        parsedV4.shouldNotBeNull()
        parsedV4.matches(InetAddress.getByName("::1")) shouldBe false
    }

    test("rejects a hostname so DNS is never consulted") {
        parseTrustedProxy("localhost").shouldBeNull()
        parseTrustedProxy("proxy.example.com").shouldBeNull()
        parseTrustedProxy("bogus.host").shouldBeNull()
    }

    test("rejects the wildcard \"*\"") {
        parseTrustedProxy("*").shouldBeNull()
    }

    test("rejects an empty string") {
        parseTrustedProxy("").shouldBeNull()
        parseTrustedProxy("   ").shouldBeNull()
    }

    test("rejects an out-of-range IPv4 prefix length") {
        parseTrustedProxy("10.0.0.0/33").shouldBeNull()
        parseTrustedProxy("10.0.0.0/-1").shouldBeNull()
    }

    test("rejects an out-of-range IPv6 prefix length") {
        parseTrustedProxy("::/129").shouldBeNull()
    }

    test("rejects a non-numeric prefix length") {
        parseTrustedProxy("10.0.0.0/abc").shouldBeNull()
    }

    test("rejects malformed IPv4 literals (octet > 255)") {
        parseTrustedProxy("999.999.999.999").shouldBeNull()
    }

    test("trims surrounding whitespace before parsing") {
        val parsed = parseTrustedProxy("   127.0.0.1   ")
        parsed.shouldBeInstanceOf<TrustedProxy.SingleIp>()
    }
})
