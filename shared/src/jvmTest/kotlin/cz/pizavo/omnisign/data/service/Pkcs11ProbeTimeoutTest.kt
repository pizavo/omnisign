package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies [Pkcs11ProbeTimeout] default, range clamping on construction, and clamping +
 * replacement via [Pkcs11ProbeTimeout.update].
 */
class Pkcs11ProbeTimeoutTest : FunSpec({

	test("defaults to the shared probe-timeout default") {
		Pkcs11ProbeTimeout().seconds shouldBe Pkcs11Prober.DEFAULT_PROBE_TIMEOUT_SECONDS
	}

	test("clamps the initial value into the accepted range") {
		Pkcs11ProbeTimeout(initialSeconds = 0).seconds shouldBe Pkcs11ProbeTimeout.MIN_SECONDS
		Pkcs11ProbeTimeout(initialSeconds = 9999).seconds shouldBe Pkcs11ProbeTimeout.MAX_SECONDS
		Pkcs11ProbeTimeout(initialSeconds = 45).seconds shouldBe 45L
	}

	test("update clamps and replaces the current value") {
		val timeout = Pkcs11ProbeTimeout(initialSeconds = 30)

		timeout.update(60)
		timeout.seconds shouldBe 60L

		timeout.update(0)
		timeout.seconds shouldBe Pkcs11ProbeTimeout.MIN_SECONDS

		timeout.update(500)
		timeout.seconds shouldBe Pkcs11ProbeTimeout.MAX_SECONDS
	}
})
