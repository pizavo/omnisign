package cz.pizavo.omnisign

import cz.pizavo.omnisign.config.TlsConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EngineSSLConnectorConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Verifies the connector [tlsConnector] adds, through real handshakes with a Netty server on
 * 127.0.0.1: a client that offers a version in [TlsConfig.enabledProtocols] connects with it, and one
 * that offers only another version is refused with a `protocol_version` alert, so the list, not the
 * JVM's defaults, decides which versions the server accepts. Without a server, it also checks that
 * the connector takes the host, port, key alias and both passwords it is given.
 *
 * The server presents the certificate of the end-to-end signer key store, which the client trusts
 * through the test CA that issued it.
 */
class TlsConnectorTest : FunSpec({

	val alias = "key"
	val password = "test1234"
	val keyStore = KeyStore.getInstance("PKCS12").apply {
		TlsConnectorTest::class.java.getResourceAsStream("/e2e/signer.test.p12").use {
			load(it, password.toCharArray())
		}
	}
	val client: SSLContext = SSLContext.getInstance("TLS").apply {
		val anchors = KeyStore.getInstance("PKCS12").apply {
			load(null, null)
			setCertificateEntry("ca", keyStore.getCertificateChain(alias).last())
		}
		val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
		init(null, trust.trustManagers, null)
	}
	val defaults = TlsConfig(keystorePath = "signer.test.p12", keyAlias = alias, port = 0)

	/**
	 * Run [block] with the port of a Netty server whose one connector is the one [tlsConnector] adds
	 * for [tls], and stop the server afterwards.
	 */
	fun withTlsServer(tls: TlsConfig, block: (Int) -> Unit) {
		val server = embeddedServer(
			Netty,
			environment = applicationEnvironment { },
			configure = { tlsConnector(tls, "127.0.0.1", keyStore, Sensitive(password), Sensitive(password)) },
		) { }
		server.start(wait = false)
		try {
			block(runBlocking { server.engine.resolvedConnectors().single().port })
		} finally {
			server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
		}
	}

	/**
	 * Complete a handshake with the server on [port] from a client that offers only [protocol], and
	 * return the version the two agreed on.
	 */
	fun handshake(port: Int, protocol: String): String =
		(client.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
			socket.soTimeout = 10_000
			socket.enabledProtocols = arrayOf(protocol)
			socket.startHandshake()
			socket.session.protocol
		}

	test("the connector takes the host, port, key alias and both passwords it is given") {
		val configuration = ApplicationEngine.Configuration().apply {
			tlsConnector(
				defaults.copy(keyAlias = "seal", port = 18_555, enabledProtocols = listOf("TLSv1.3")),
				"192.0.2.7",
				keyStore,
				Sensitive("store-password"),
				Sensitive("key-password"),
			)
		}
		val connector = configuration.connectors.single().shouldBeInstanceOf<EngineSSLConnectorConfig>()
		connector.type shouldBe ConnectorType.HTTPS
		connector.host shouldBe "192.0.2.7"
		connector.port shouldBe 18_555
		connector.keyStore shouldBeSameInstanceAs keyStore
		connector.keyAlias shouldBe "seal"
		connector.keyStorePassword().concatToString() shouldBe "store-password"
		connector.privateKeyPassword().concatToString() shouldBe "key-password"
		connector.enabledProtocols shouldBe listOf("TLSv1.3")
	}

	test("by default the connector accepts a client that offers only TLS 1.3 and one that offers only TLS 1.2") {
		withTlsServer(defaults) { port ->
			handshake(port, "TLSv1.3") shouldBe "TLSv1.3"
			handshake(port, "TLSv1.2") shouldBe "TLSv1.2"
		}
	}

	test("a connector limited to TLS 1.3 refuses a client that offers only TLS 1.2") {
		withTlsServer(defaults.copy(enabledProtocols = listOf("TLSv1.3"))) { port ->
			handshake(port, "TLSv1.3") shouldBe "TLSv1.3"
			shouldThrow<SSLHandshakeException> { handshake(port, "TLSv1.2") }.message shouldContain "protocol_version"
		}
	}

	test("a connector limited to TLS 1.2 refuses a client that offers only TLS 1.3") {
		withTlsServer(defaults.copy(enabledProtocols = listOf("TLSv1.2"))) { port ->
			handshake(port, "TLSv1.2") shouldBe "TLSv1.2"
			shouldThrow<SSLHandshakeException> { handshake(port, "TLSv1.3") }.message shouldContain "protocol_version"
		}
	}
})
