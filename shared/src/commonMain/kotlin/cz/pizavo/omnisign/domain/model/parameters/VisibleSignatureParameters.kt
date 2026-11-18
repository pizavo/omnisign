package cz.pizavo.omnisign.domain.model.parameters

/**
 * Parameters for visible signature appearance.
 *
 * The box's position and size are in PDF user units (1 unit = 1/72 inch), and its position is that
 * of its top-left corner, measured from the page's top-left corner, as DSS measures it: [y] counts
 * down from the top of the page, where PDF's own coordinates count up from the bottom. A box given
 * neither [text] nor [imagePath] shows a label saying who signed and when, since DSS would draw
 * nothing in it.
 *
 * @property page The page the box is drawn on, counting from 1.
 * @property x The distance of the box's left edge from the page's left edge.
 * @property y The distance of the box's top edge from the page's top edge.
 * @property width The box's width.
 * @property height The box's height.
 * @property text The text the box shows. A blank one counts as none.
 * @property imagePath The path of an image the box shows.
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

