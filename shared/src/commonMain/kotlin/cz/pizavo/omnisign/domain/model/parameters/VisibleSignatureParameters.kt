package cz.pizavo.omnisign.domain.model.parameters

/**
 * Parameters for visible signature appearance.
 */
data class VisibleSignatureParameters(
    val page: Int = 1,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String? = null,
    val imagePath: String? = null
)

