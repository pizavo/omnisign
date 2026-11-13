package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Verifies that [ValidationConfig] leaves its unset inheritable settings out of the JSON, even for an
 * encoder that writes defaults, and that it reads both configurations that leave them out and
 * configurations written while they always held a value.
 */
class ValidationConfigTest : FunSpec({

	val writesDefaults = Json { encodeDefaults = true }
	val readsLeniently = Json { ignoreUnknownKeys = true }
	val inheritableKeys = listOf("policyType", "checkRevocation", "useEuLotl")

	test("unset inheritable settings are left out even by an encoder that writes defaults") {
		val written = writesDefaults.encodeToJsonElement(ValidationConfig.serializer(), ValidationConfig()).jsonObject

		inheritableKeys.filter { it in written.keys } shouldBe emptyList()
		("customTrustedLists" in written.keys) shouldBe true
	}

	test("set inheritable settings are written") {
		val written = writesDefaults.encodeToJsonElement(
			ValidationConfig.serializer(),
			ValidationConfig(policyType = ValidationPolicyType.CUSTOM_FILE, checkRevocation = false, useEuLotl = false),
		).jsonObject

		written["policyType"] shouldBe JsonPrimitive("CUSTOM_FILE")
		written["checkRevocation"] shouldBe JsonPrimitive(false)
		written["useEuLotl"] shouldBe JsonPrimitive(false)
	}

	test("settings left out of the JSON are read as inherited") {
		val read = readsLeniently.decodeFromString(ValidationConfig.serializer(), "{}")

		read.policyType.shouldBeNull()
		read.checkRevocation.shouldBeNull()
		read.useEuLotl.shouldBeNull()
	}

	test("settings written as null are read as inherited") {
		val read = readsLeniently.decodeFromString(
			ValidationConfig.serializer(),
			"""{"policyType":null,"checkRevocation":null,"useEuLotl":null}""",
		)

		read.policyType.shouldBeNull()
		read.checkRevocation.shouldBeNull()
		read.useEuLotl.shouldBeNull()
	}

	test("a configuration written while the settings always held a value keeps them") {
		val read = readsLeniently.decodeFromString(
			ValidationConfig.serializer(),
			"""{"policyType":"CUSTOM_FILE","checkRevocation":false,"useEuLotl":false}""",
		)

		read.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
		read.checkRevocation shouldBe false
		read.useEuLotl shouldBe false
	}
})
