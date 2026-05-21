package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [validateTransportSecurity] and [isLoopbackHost].
 *
 * The validation table in [validateTransportSecurity]'s KDoc is the operator-facing
 * contract; one test per row pins each path.
 */
class TransportSecurityValidatorTest : FunSpec({

    val placeholderCors = CorsConfig(allowedOrigins = listOf("*"))
    val tlsBlock = TlsConfig(keystorePath = "/tmp/ks.p12")
    val proxyEnabled = ProxyConfig(enabled = true, trusted = listOf("127.0.0.1"))

    test("loopback bind on 127.0.0.1 passes without TLS or proxy") {
        validateTransportSecurity(ServerConfig(host = "127.0.0.1", cors = placeholderCors))
    }

    test("loopback bind on ::1 passes without TLS or proxy") {
        validateTransportSecurity(ServerConfig(host = "::1", cors = placeholderCors))
    }

    test("loopback bind via localhost alias passes without TLS or proxy") {
        validateTransportSecurity(ServerConfig(host = "localhost", cors = placeholderCors))
    }

    test("non-loopback 0.0.0.0 without TLS or proxy fails with operator-actionable message") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateTransportSecurity(ServerConfig(host = "0.0.0.0", cors = placeholderCors))
        }
        ex.message!! shouldContain "0.0.0.0"
        ex.message!! shouldContain "proxy.enabled: true"
        ex.message!! shouldContain "tls:"
    }

    test("non-loopback with proxy.enabled=true passes") {
        validateTransportSecurity(
            ServerConfig(host = "0.0.0.0", proxy = proxyEnabled, cors = placeholderCors),
        )
    }

    test("non-loopback with tls configured passes") {
        validateTransportSecurity(
            ServerConfig(host = "0.0.0.0", tls = tlsBlock, cors = placeholderCors),
        )
    }

    test("non-loopback with both proxy and tls passes") {
        validateTransportSecurity(
            ServerConfig(
                host = "0.0.0.0",
                proxy = proxyEnabled,
                tls = tlsBlock,
                cors = placeholderCors,
            ),
        )
    }

    test("non-loopback with proxy block but enabled=false still fails") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateTransportSecurity(
                ServerConfig(
                    host = "10.0.0.5",
                    proxy = ProxyConfig(enabled = false, trusted = emptyList()),
                    cors = placeholderCors,
                ),
            )
        }
        ex.message!! shouldContain "10.0.0.5"
    }

    test("non-loopback private IP also requires TLS or proxy") {
        shouldThrow<IllegalArgumentException> {
            validateTransportSecurity(ServerConfig(host = "192.168.1.10", cors = placeholderCors))
        }
    }

    test("isLoopbackHost recognises the three accepted forms") {
        isLoopbackHost("127.0.0.1") shouldBe true
        isLoopbackHost("::1") shouldBe true
        isLoopbackHost("localhost") shouldBe true
    }

    test("isLoopbackHost rejects other 127.0.0.0/8 addresses (strict literal match)") {
        isLoopbackHost("127.0.0.2") shouldBe false
        isLoopbackHost("127.1.0.0") shouldBe false
    }

    test("isLoopbackHost rejects arbitrary hostnames and non-loopback IPs") {
        isLoopbackHost("0.0.0.0") shouldBe false
        isLoopbackHost("example.com") shouldBe false
        isLoopbackHost("192.168.1.1") shouldBe false
        isLoopbackHost("") shouldBe false
    }
})
