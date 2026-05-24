package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Unit tests verifying that trusted certificates never round-trip through the *configuration*.
 *
 * Directly-trusted certificates live in the app-managed trust store, not in the config. The edit
 * states stage certificate changes for the store separately (baseline + pending add/remove), but
 * [GlobalConfigEditState.from]/[ProfileEditState.from] must ignore any certificates present in a
 * config's validation block, and `toGlobalConfig`/`toProfileConfig` must never write certificates
 * back into the config.
 */
class TrustedCertificateEditStateTest : FunSpec({

    fun cert(type: TrustedCertificateType = TrustedCertificateType.CA) =
        TrustedCertificateRef(inline = "AAAA", type = type)

    context("ProfileEditState") {

        test("from() ignores any trusted certificates in the profile validation config") {
            val profile = ProfileConfig(
                name = "test",
                validation = ValidationConfig(
                    trustedCertificates = listOf(cert()),
                    customTrustedLists = listOf(CustomTrustedListConfig(name = "tl", source = "https://x/tl.xml")),
                ),
            )

            val state = ProfileEditState.from(profile)

            state.customTrustedLists shouldHaveSize 1
        }

        test("toProfileConfig() never writes trusted certificates into the config") {
            val state = ProfileEditState(
                profileName = "p1",
                customTrustedLists = listOf(CustomTrustedListConfig(name = "tl", source = "https://x/tl.xml")),
            )

            val config = state.toProfileConfig()

            config.validation.shouldNotBeNull()
            config.validation!!.trustedCertificates.shouldBeEmpty()
        }

        test("toProfileConfig() sets validation to null when no trusted lists") {
            val state = ProfileEditState(profileName = "p1")

            val config = state.toProfileConfig()

            config.validation.shouldBeNull()
        }
    }

    context("GlobalConfigEditState") {

        test("from() ignores any trusted certificates in the global validation config") {
            val global = GlobalConfig(
                validation = ValidationConfig(
                    trustedCertificates = listOf(cert()),
                    customTrustedLists = listOf(CustomTrustedListConfig(name = "tl", source = "https://x/tl.xml")),
                ),
            )

            val state = GlobalConfigEditState.from(global)

            state.customTrustedLists shouldHaveSize 1
        }

        test("toGlobalConfig() never writes trusted certificates into the config") {
            val state = GlobalConfigEditState(
                customTrustedLists = listOf(CustomTrustedListConfig(name = "tl", source = "https://x/tl.xml")),
            )

            val config = state.toGlobalConfig()

            config.validation.trustedCertificates.shouldBeEmpty()
            config.validation.customTrustedLists shouldHaveSize 1
        }
    }
})
