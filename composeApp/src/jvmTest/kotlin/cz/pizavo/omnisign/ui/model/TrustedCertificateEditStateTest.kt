package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for trusted certificate handling in [ProfileEditState] and [GlobalConfigEditState].
 */
class TrustedCertificateEditStateTest : FunSpec({

    fun cert(name: String, type: TrustedCertificateType = TrustedCertificateType.CA) =
        TrustedCertificateConfig(
            name = name,
            type = type,
            certificateBase64 = "AAAA",
            subjectDN = "CN=$name",
        )

    context("ProfileEditState") {

        test("from() populates trustedCertificates from profile validation config") {
            val profile = ProfileConfig(
                name = "test",
                validation = ValidationConfig(
                    trustedCertificates = listOf(cert("ca1"), cert("tsa1", TrustedCertificateType.TSA)),
                ),
            )

            val state = ProfileEditState.from(profile)

            state.trustedCertificates shouldHaveSize 2
            state.trustedCertificates[0].name shouldBe "ca1"
            state.trustedCertificates[1].type shouldBe TrustedCertificateType.TSA
        }

        test("from() yields empty list when profile has no validation config") {
            val profile = ProfileConfig(name = "bare")

            val state = ProfileEditState.from(profile)

            state.trustedCertificates.shouldBeEmpty()
        }

        test("toProfileConfig() includes validation with trustedCertificates") {
            val state = ProfileEditState(
                profileName = "p1",
                trustedCertificates = listOf(cert("root-ca")),
            )

            val config = state.toProfileConfig()

            config.validation.shouldNotBeNull()
            config.validation!!.trustedCertificates shouldHaveSize 1
            config.validation!!.trustedCertificates.first().name shouldBe "root-ca"
        }

        test("toProfileConfig() sets validation to null when no trusted certificates") {
            val state = ProfileEditState(
                profileName = "p1",
                trustedCertificates = emptyList(),
            )

            val config = state.toProfileConfig()

            config.validation.shouldBeNull()
        }

        test("certAddError defaults to null") {
            val state = ProfileEditState(profileName = "p")

            state.certAddError.shouldBeNull()
        }
    }

    context("GlobalConfigEditState") {

        test("from() populates trustedCertificates from global validation config") {
            val global = GlobalConfig(
                validation = ValidationConfig(
                    trustedCertificates = listOf(cert("global-ca")),
                ),
            )

            val state = GlobalConfigEditState.from(global)

            state.trustedCertificates shouldHaveSize 1
            state.trustedCertificates.first().name shouldBe "global-ca"
        }

        test("from() yields empty list when no trusted certificates configured") {
            val global = GlobalConfig()

            val state = GlobalConfigEditState.from(global)

            state.trustedCertificates.shouldBeEmpty()
        }

        test("toGlobalConfig() includes trustedCertificates in validation config") {
            val state = GlobalConfigEditState(
                trustedCertificates = listOf(cert("root"), cert("intermediate")),
            )

            val config = state.toGlobalConfig()

            config.validation.trustedCertificates shouldHaveSize 2
            config.validation.trustedCertificates.map { it.name } shouldBe listOf("root", "intermediate")
        }

        test("certAddError defaults to null") {
            val state = GlobalConfigEditState()

            state.certAddError.shouldBeNull()
        }
    }
})

