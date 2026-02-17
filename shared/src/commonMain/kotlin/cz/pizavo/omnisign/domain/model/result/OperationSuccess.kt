package cz.pizavo.omnisign.domain.model.result

/**
 * Success result that can contain warnings.
 */
data class OperationSuccess<T>(
    val value: T,
    val warnings: List<OperationWarning> = emptyList()
)

