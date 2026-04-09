package cz.pizavo.omnisign.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Determines whether a set of IdP claims satisfies all entries in [requiredClaims].
 *
 * For each `(claimName, requiredValues)` pair in [requiredClaims], the user's actual claim
 * value must contain at least one string that appears in [requiredValues]. All entries must
 * be satisfied simultaneously (logical AND across claim names, logical OR within each claim's
 * value list).
 *
 * Both single-valued claims (e.g. `schac_home_organization: "osu.cz"`) and multivalued
 * claims (e.g. `eduperson_scoped_affiliation: ["staff@osu.cz", "member@osu.cz"]`) are
 * handled correctly. A JSON array claim matches when any of its elements is in
 * [requiredValues]; a JSON string claim matches when it equals one of [requiredValues].
 *
 * JSON `null` values and non-primitive / non-array elements are treated as absent and never
 * match. When [requiredClaims] is `null` or empty, all users are accepted unconditionally.
 *
 * @param claims The Raw JSON claims object from the OIDC `/userinfo` endpoint.
 * @param requiredClaims Map of claim name to the set of acceptable values, or `null` to
 *   disable the check entirely.
 * @return `true` when every required claim constraint is satisfied, or when [requiredClaims]
 *   is `null` or empty.
 */
internal fun areRequiredClaimsSatisfied(
    claims: JsonObject,
    requiredClaims: Map<String, List<String>>?,
): Boolean {
    if (requiredClaims.isNullOrEmpty()) return true
    return requiredClaims.all { (claimName, requiredValues) ->
        val element = claims[claimName] ?: return@all false
        val actualValues: List<String> = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }
        actualValues.any { it in requiredValues }
    }
}

