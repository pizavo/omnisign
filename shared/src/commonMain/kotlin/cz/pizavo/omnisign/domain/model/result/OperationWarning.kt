package cz.pizavo.omnisign.domain.model.result

/**
 * Warnings that don't prevent operation completion but should be shown to the user.
 */
data class OperationWarning(
    val message: String,
    val details: String? = null,
    val severity: WarningSeverity = WarningSeverity.MEDIUM
)

