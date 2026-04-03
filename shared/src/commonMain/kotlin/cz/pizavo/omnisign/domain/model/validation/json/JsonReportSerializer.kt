package cz.pizavo.omnisign.domain.model.validation.json

import kotlinx.serialization.json.Json

/**
 * Pre-configured [Json] instance used for serializing validation report DTOs.
 *
 * Pretty-printing is enabled, so the exported files are human-readable.
 */
private val reportJson = Json { prettyPrint = true }

/**
 * Serialize a [JsonValidationReport] to a pretty-printed JSON string.
 */
fun JsonValidationReport.toJsonString(): String =
    reportJson.encodeToString(this)

