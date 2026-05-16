package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Verifies the deterministic, SunPKCS#11-free contract of [Pkcs11NoLoginCertProbe]:
 * a non-existent library short-circuits to a not-loaded result without ever touching
 * the security provider, so the probe is safe to call from the diagnostics sweep.
 */
class Pkcs11NoLoginCertProbeTest : FunSpec({

	test("enumerate reports not-loaded for a missing library without invoking SunPKCS11") {
		val result = Pkcs11NoLoginCertProbe().enumerate(
			tokenName = "Phantom Token",
			libraryPath = "/does/not/exist/eTPKCS11.dll",
			slotId = 3L,
		)

		result.loaded shouldBe false
		result.error!! shouldContain "library not found"
		result.entries.shouldBeEmpty()
		result.tokenName shouldBe "Phantom Token"
		result.libraryPath shouldBe "/does/not/exist/eTPKCS11.dll"
		result.slotId shouldBe 3L
	}
})
