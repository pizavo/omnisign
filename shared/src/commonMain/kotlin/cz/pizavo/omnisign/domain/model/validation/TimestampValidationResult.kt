package cz.pizavo.omnisign.domain.model.validation

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Validation result for a timestamp.
 *
 * @property timestampId DSS internal identifier for this timestamp token.
 * @property type Human-readable timestamp type (e.g. "Signature timestamp", "Archive timestamp").
 * @property indication Overall validation indication for this timestamp.
 * @property subIndication Optional sub-indication providing additional detail.
 * @property productionTime Point in time at which the timestamp was produced.
 * @property qualification Optional qualification level (e.g. QTSA).
 * @property tsaSubjectDN Distinguished name of the Timestamp Authority's certificate subject.
 * @property euLotlBacked True when the TSA's trust anchor is on the EU LOTL (or a national trusted
 *   list that is a member of it), as opposed to a commercial or user-added custom-trusted TSA.
 * @property errors Validation errors for this timestamp.
 * @property warnings Validation warnings for this timestamp.
 * @property infos Informational messages for this timestamp.
 * @property policyUntrusted True when DSS validated the timestamp but the per-reference trust
 *   policy distrusts its terminating anchor for timestamping (it is trusted as a CA only).
 *   Distinct from a cryptographic failure; the reason is appended to [errors].
 */
@Serializable
data class TimestampValidationResult(
    val timestampId: String,
    val type: String,
    val indication: ValidationIndication,
    val subIndication: String? = null,
    val productionTime: Instant,
    val qualification: String? = null,
    val tsaSubjectDN: String? = null,
    val euLotlBacked: Boolean = false,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val infos: List<String> = emptyList(),
    val policyUntrusted: Boolean = false,
)
