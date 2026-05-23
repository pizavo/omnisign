package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [validateProxyConfig].
 *
 * Covers every row of the validation table in [ProxyConfig]'s KDoc — the table is the
 * operator-facing contract, so each row needs a passing or failing test that pins it.
 */
class ProxyConfigValidatorTest : FunSpec({

    test("returns disabled mode when proxy config is null") {
        val result = validateProxyConfig(null)
        result.enabled shouldBe false
        result.trustedProxies shouldHaveSize 0
    }

    test("returns disabled mode when enabled is false and trusted is empty") {
        val result = validateProxyConfig(ProxyConfig(enabled = false, trusted = emptyList()))
        result.enabled shouldBe false
        result.trustedProxies shouldHaveSize 0
    }

    test("returns disabled mode and ignores trusted when enabled is false but trusted is non-empty") {
        val result = validateProxyConfig(
            ProxyConfig(enabled = false, trusted = listOf("127.0.0.1")),
        )
        result.enabled shouldBe false
        result.trustedProxies shouldHaveSize 0
    }

    test("rejects enabled=true with empty trusted list and names the field") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateProxyConfig(ProxyConfig(enabled = true, trusted = emptyList()))
        }
        ex.message!! shouldContain "proxy.trusted is empty"
        ex.message!! shouldContain "[\"127.0.0.1\", \"::1\"]"
    }

    test("rejects enabled=true with \"*\" anywhere in trusted") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateProxyConfig(ProxyConfig(enabled = true, trusted = listOf("127.0.0.1", "*")))
        }
        ex.message!! shouldContain "\"*\""
        ex.message!! shouldContain "defeat the trust boundary"
    }

    test("rejects a malformed entry naming the index and the offending value") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateProxyConfig(
                ProxyConfig(enabled = true, trusted = listOf("127.0.0.1", "bogus.host")),
            )
        }
        ex.message!! shouldContain "entry 2"
        ex.message!! shouldContain "'bogus.host'"
        ex.message!! shouldContain "Hostnames are not accepted"
    }

    test("accepts the same-host loopback pair") {
        val result = validateProxyConfig(
            ProxyConfig(enabled = true, trusted = listOf("127.0.0.1", "::1")),
        )
        result.enabled shouldBe true
        result.trustedProxies shouldHaveSize 2
        result.trustedProxies[0].shouldBeInstanceOf<TrustedProxy.SingleIp>()
        result.trustedProxies[1].shouldBeInstanceOf<TrustedProxy.SingleIp>()
    }

    test("accepts CIDR ranges and returns them as CidrRange entries") {
        val result = validateProxyConfig(
            ProxyConfig(enabled = true, trusted = listOf("10.0.0.0/8", "fc00::/7")),
        )
        result.enabled shouldBe true
        result.trustedProxies shouldHaveSize 2
        result.trustedProxies[0].shouldBeInstanceOf<TrustedProxy.CidrRange>()
        result.trustedProxies[1].shouldBeInstanceOf<TrustedProxy.CidrRange>()
    }
})
