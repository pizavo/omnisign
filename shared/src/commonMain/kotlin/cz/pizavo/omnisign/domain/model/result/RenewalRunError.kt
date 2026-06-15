package cz.pizavo.omnisign.domain.model.result

import kotlinx.serialization.Serializable

/**
 * A single file-scoped error captured in a [RenewalRunRecord].
 *
 * @property path Absolute path to the file that failed.
 * @property message Human-readable reason it failed.
 */
@Serializable
data class RenewalRunError(
    val path: String,
    val message: String,
)
