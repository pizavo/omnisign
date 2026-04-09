package cz.pizavo.omnisign.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Unit tests for [areRequiredClaimsSatisfied].
 */
class OidcClaimsFilterTest : FunSpec({

    test("returns true when requiredClaims is null") {
        val claims = buildJsonObject { put("sub", "user1") }
        areRequiredClaimsSatisfied(claims, null) shouldBe true
    }

    test("returns true when requiredClaims is empty") {
        val claims = buildJsonObject { put("sub", "user1") }
        areRequiredClaimsSatisfied(claims, emptyMap()) shouldBe true
    }

    test("returns true when single-valued string claim matches") {
        val claims = buildJsonObject { put("schac_home_organization", "osu.cz") }
        areRequiredClaimsSatisfied(
            claims,
            mapOf("schac_home_organization" to listOf("osu.cz")),
        ) shouldBe true
    }

    test("returns false when single-valued string claim does not match") {
        val claims = buildJsonObject { put("schac_home_organization", "vsb.cz") }
        areRequiredClaimsSatisfied(
            claims,
            mapOf("schac_home_organization" to listOf("osu.cz")),
        ) shouldBe false
    }

    test("returns true when multi-valued array claim contains a required value") {
        val claims = buildJsonObject {
            putJsonArray("eduperson_scoped_affiliation") {
                add(kotlinx.serialization.json.JsonPrimitive("member@osu.cz"))
                add(kotlinx.serialization.json.JsonPrimitive("staff@osu.cz"))
            }
        }
        areRequiredClaimsSatisfied(
            claims,
            mapOf("eduperson_scoped_affiliation" to listOf("staff@osu.cz", "faculty@osu.cz")),
        ) shouldBe true
    }

    test("returns false when multi-valued array claim contains no required value") {
        val claims = buildJsonObject {
            putJsonArray("eduperson_scoped_affiliation") {
                add(kotlinx.serialization.json.JsonPrimitive("student@osu.cz"))
                add(kotlinx.serialization.json.JsonPrimitive("member@osu.cz"))
            }
        }
        areRequiredClaimsSatisfied(
            claims,
            mapOf("eduperson_scoped_affiliation" to listOf("staff@osu.cz", "faculty@osu.cz")),
        ) shouldBe false
    }

    test("returns true only when all required claims are satisfied") {
        val claims = buildJsonObject {
            put("schac_home_organization", "osu.cz")
            putJsonArray("eduperson_scoped_affiliation") {
                add(kotlinx.serialization.json.JsonPrimitive("staff@osu.cz"))
            }
        }
        areRequiredClaimsSatisfied(
            claims,
            mapOf(
                "schac_home_organization" to listOf("osu.cz"),
                "eduperson_scoped_affiliation" to listOf("staff@osu.cz", "faculty@osu.cz"),
            ),
        ) shouldBe true
    }

    test("returns false when one of multiple required claims is not satisfied") {
        val claims = buildJsonObject {
            put("schac_home_organization", "osu.cz")
            putJsonArray("eduperson_scoped_affiliation") {
                add(kotlinx.serialization.json.JsonPrimitive("student@osu.cz"))
            }
        }
        areRequiredClaimsSatisfied(
            claims,
            mapOf(
                "schac_home_organization" to listOf("osu.cz"),
                "eduperson_scoped_affiliation" to listOf("staff@osu.cz", "faculty@osu.cz"),
            ),
        ) shouldBe false
    }

    test("returns false when required claim key is absent from claims") {
        val claims = buildJsonObject { put("sub", "user1") }
        areRequiredClaimsSatisfied(
            claims,
            mapOf("schac_home_organization" to listOf("osu.cz")),
        ) shouldBe false
    }

    test("ignores JSON null claim values") {
        val claims = buildJsonObject { put("schac_home_organization", null as String?) }
        areRequiredClaimsSatisfied(
            claims,
            mapOf("schac_home_organization" to listOf("osu.cz")),
        ) shouldBe false
    }
})

