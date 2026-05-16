package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.util.Base64

/**
 * Deterministic, native-free contract checks for [parseProbeCertificates]: it must consider
 * only well-formed `CERT` lines, never throw, and silently drop everything else (token
 * identity output, stray logging, malformed or non-certificate payloads).  The positive
 * "real DER → parsed subject" path is exercised by running `diagnose pkcs11` against an
 * actual token, which is the whole point of the Step-1 experiment.
 */
class ParseProbeCertificatesTest : FunSpec({

	test("ignores token-identity lines and blank output") {
		parseProbeCertificates("VP-SafeNet\t3D592EB075CF7A6E\t0\n\n").shouldBeEmpty()
		parseProbeCertificates("").shouldBeEmpty()
	}

	test("drops CERT lines that do not have exactly five tab-separated fields") {
		parseProbeCertificates("CERT\t0\tdeadbeef").shouldBeEmpty()
	}

	test("drops CERT lines whose DER field is not valid Base64") {
		parseProbeCertificates("CERT\t0\tab\tbGFiZWw=\t!!! not base64 !!!").shouldBeEmpty()
	}

	test("drops CERT lines whose Base64 payload is not an X.509 certificate") {
		val notACert = Base64.getEncoder().encodeToString("hello".toByteArray())
		parseProbeCertificates("CERT\t0\tab\tbGFiZWw=\t$notACert").shouldBeEmpty()
	}
})
