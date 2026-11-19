package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [validateTlsConfig].
 *
 * A configuration without a `tls` block, the default versions, and TLS 1.3 or TLS 1.2 alone are
 * accepted. An empty `tls.enabledProtocols` and one that lists a version the server does not offer
 * fail startup, with a message that names the field, the version refused and the versions accepted.
 */
class TlsConfigValidatorTest : FunSpec({

	val tls = TlsConfig(keystorePath = "/etc/omnisign/tls.p12")

	test("accepts no tls block, the default versions, and TLS 1.3 or TLS 1.2 alone") {
		shouldNotThrowAny {
			validateTlsConfig(null)
			validateTlsConfig(tls)
			validateTlsConfig(tls.copy(enabledProtocols = listOf("TLSv1.3")))
			validateTlsConfig(tls.copy(enabledProtocols = listOf("TLSv1.2")))
			validateTlsConfig(tls.copy(enabledProtocols = listOf("TLSv1.2", "TLSv1.3")))
		}
	}

	test("rejects an empty list with a message naming the field and the versions it accepts") {
		val ex = shouldThrow<IllegalArgumentException> {
			validateTlsConfig(tls.copy(enabledProtocols = emptyList()))
		}
		ex.message!! shouldContain "tls.enabledProtocols is empty"
		ex.message!! shouldContain "TLSv1.3 and TLSv1.2"
	}

	test("rejects a version the server does not offer, or a misspelt one, naming it") {
		for (version in listOf("TLSv1.1", "TLSv1", "SSLv3", "tlsv1.3", "TLSv1.4")) {
			val ex = shouldThrow<IllegalArgumentException> {
				validateTlsConfig(tls.copy(enabledProtocols = listOf("TLSv1.3", version)))
			}
			ex.message!! shouldContain "tls.enabledProtocols lists $version,"
			ex.message!! shouldContain "TLSv1.3 and TLSv1.2"
		}
	}
})
