package cz.pizavo.omnisign.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [validateOperationsConfig].
 *
 * Pins the operator-facing contract: an empty `operations.allowed` is a startup failure whose
 * message names the field and lists the operations, while any non-empty set — a single
 * operation or a combination — is accepted. Disabling a subset is valid; disabling everything
 * is not. An empty `operations.certificateAliases` is likewise rejected while SIGN is enabled
 * (no certificate would be usable) but left alone when SIGN is off, where the field is inert.
 * A `signingKeystorePath` set while SIGN is off is rejected too — the keystore would never load.
 */
class OperationsConfigValidatorTest : FunSpec({

    test("rejects an empty allowed set with a message naming the field and operations") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateOperationsConfig(OperationsConfig(allowed = emptySet()))
        }
        ex.message!! shouldContain "operations.allowed"
        ex.message!! shouldContain "VALIDATE"
        ex.message!! shouldContain "SIGN"
        ex.message!! shouldContain "TIMESTAMP"
    }

    test("accepts the validate-only default") {
        shouldNotThrowAny {
            validateOperationsConfig(OperationsConfig(allowed = setOf(AllowedOperation.VALIDATE)))
        }
    }

    test("accepts a subset that disables VALIDATE") {
        shouldNotThrowAny {
            validateOperationsConfig(
                OperationsConfig(allowed = setOf(AllowedOperation.SIGN, AllowedOperation.TIMESTAMP)),
            )
        }
    }

    test("accepts all three operations") {
        shouldNotThrowAny {
            validateOperationsConfig(
                OperationsConfig(
                    allowed = setOf(
                        AllowedOperation.VALIDATE,
                        AllowedOperation.SIGN,
                        AllowedOperation.TIMESTAMP,
                    ),
                ),
            )
        }
    }

    test("rejects an empty certificateAliases while SIGN is enabled") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateOperationsConfig(
                OperationsConfig(
                    allowed = setOf(AllowedOperation.SIGN, AllowedOperation.VALIDATE),
                    certificateAliases = emptyList(),
                ),
            )
        }
        ex.message!! shouldContain "certificateAliases"
    }

    test("accepts an empty certificateAliases when SIGN is not enabled") {
        shouldNotThrowAny {
            validateOperationsConfig(
                OperationsConfig(
                    allowed = setOf(AllowedOperation.VALIDATE),
                    certificateAliases = emptyList(),
                ),
            )
        }
    }

    test("accepts a non-empty certificateAliases with SIGN enabled") {
        shouldNotThrowAny {
            validateOperationsConfig(
                OperationsConfig(
                    allowed = setOf(AllowedOperation.SIGN),
                    certificateAliases = listOf("university-seal"),
                ),
            )
        }
    }

    test("rejects a signingKeystorePath set while SIGN is not enabled") {
        val ex = shouldThrow<IllegalArgumentException> {
            validateOperationsConfig(
                OperationsConfig(
                    allowed = setOf(AllowedOperation.VALIDATE),
                    signingKeystorePath = "/etc/omnisign/signing.p12",
                ),
            )
        }
        ex.message!! shouldContain "signingKeystorePath"
    }

    test("accepts a signingKeystorePath when SIGN is enabled") {
        shouldNotThrowAny {
            validateOperationsConfig(
                OperationsConfig(
                    allowed = setOf(AllowedOperation.SIGN),
                    signingKeystorePath = "/etc/omnisign/signing.p12",
                ),
            )
        }
    }
})