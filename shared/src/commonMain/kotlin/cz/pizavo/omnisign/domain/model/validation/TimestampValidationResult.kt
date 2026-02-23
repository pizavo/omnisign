
package cz.pizavo.omnisign.domain.model.validation

/**
 * Validation result for a timestamp.
 *
 * @property timestampId DSS internal identifier for this timestamp token.
 * @property type Human-readable timestamp type (e.g. "Signature timestamp", "Archive timestamp").
 * @property indication Overall validation indication for this timestamp.
 * @property subIndication Optional sub-indication providing additional detail.
 * @property productionTime The time at which the timestamp was produced.
 * @property qualification Optional qualification level (e.g. QTSA).
 * @property tsaSubjectDN Distinguished name of the Timestamp Authority's certificate subject.
 * @property errors Validation errors for this timestamp.
 * @property warnings Validation warnings for this timestamp.
 * @property infos Informational messages for this timestamp.
 */
data class TimestampValidationResult(
    val timestampId: String,
    val type: String,
    val indication: ValidationIndication,
    val subIndication: String? = null,
    val productionTime: String,
    val qualification: String? = null,
    val tsaSubjectDN: String? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val infos: List<String> = emptyList()
)

