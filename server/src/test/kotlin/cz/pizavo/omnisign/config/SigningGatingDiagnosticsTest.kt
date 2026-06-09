package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [unsatisfiableSigningTargets].
 *
 * Pins the startup-diagnostic contract: the warning fires only when SIGN is reachable but
 * TIMESTAMP is not, names every signing target (global default and profiles) whose effective
 * level mandates a timestamp, and stays silent for B-B targets and for any configuration where
 * signing is disabled or timestamping is available.
 */
class SigningGatingDiagnosticsTest : FunSpec({

    fun ops(vararg allowed: AllowedOperation) = OperationsConfig(allowed = allowed.toSet())

    test("empty when SIGN is not enabled even though the global default needs a timestamp") {
        val config = AppConfig(global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA))
        unsatisfiableSigningTargets(ops(AllowedOperation.VALIDATE), config).shouldBeEmpty()
    }

    test("empty when TIMESTAMP is enabled alongside SIGN") {
        val config = AppConfig(global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA))
        unsatisfiableSigningTargets(ops(AllowedOperation.SIGN, AllowedOperation.TIMESTAMP), config).shouldBeEmpty()
    }

    test("empty when SIGN is on, TIMESTAMP is off, and every level is B-B") {
        val config = AppConfig(
            global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B),
            profiles = mapOf(
                "plain" to ProfileConfig(name = "plain", signatureLevel = SignatureLevel.PADES_BASELINE_B),
            ),
        )
        unsatisfiableSigningTargets(ops(AllowedOperation.SIGN), config).shouldBeEmpty()
    }

    test("flags the global default when it mandates a timestamp and TIMESTAMP is off") {
        val config = AppConfig(global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_T))
        val result = unsatisfiableSigningTargets(ops(AllowedOperation.SIGN), config)
        result shouldHaveSize 1
        result.single() shouldContain "global default"
    }

    test("flags a profile that overrides to a timestamp level while the global default is B-B") {
        val config = AppConfig(
            global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B),
            profiles = mapOf(
                "archival" to ProfileConfig(name = "archival", signatureLevel = SignatureLevel.PADES_BASELINE_LTA),
            ),
        )
        val result = unsatisfiableSigningTargets(ops(AllowedOperation.SIGN, AllowedOperation.VALIDATE), config)
        result shouldHaveSize 1
        result.single() shouldContain "archival"
    }

    test("flags the global default and inheriting profiles but not a profile downgraded to B-B") {
        val config = AppConfig(
            global = GlobalConfig(defaultSignatureLevel = SignatureLevel.PADES_BASELINE_LTA),
            profiles = mapOf(
                "inherits" to ProfileConfig(name = "inherits"),
                "downgraded" to ProfileConfig(name = "downgraded", signatureLevel = SignatureLevel.PADES_BASELINE_B),
            ),
        )
        val result = unsatisfiableSigningTargets(ops(AllowedOperation.SIGN), config)
        result shouldHaveSize 2
        result.any { it.contains("global default") } shouldBe true
        result.any { it.contains("inherits") } shouldBe true
        result.none { it.contains("downgraded") } shouldBe true
    }
})
