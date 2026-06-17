package cz.pizavo.omnisign.domain.model.result

import kotlinx.serialization.Serializable

/**
 * Per-job rollup captured in a [RenewalRunRecord].
 *
 * @property name The renewal job name.
 * @property renewed Number of files re-timestamped in this job.
 * @property errors Number of files that failed in this job.
 */
@Serializable
data class RenewalRunJobSummary(
    val name: String,
    val renewed: Int,
    val errors: Int,
)
