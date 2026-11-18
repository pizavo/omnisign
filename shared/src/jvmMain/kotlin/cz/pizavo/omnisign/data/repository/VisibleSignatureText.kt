package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.model.value.InstantFormatter
import eu.europa.esig.dss.enumerations.TextWrapping
import eu.europa.esig.dss.pades.DSSFileFont
import eu.europa.esig.dss.pades.SignatureImageTextParameters
import kotlinx.datetime.TimeZone
import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.io.RandomAccessReadBuffer
import kotlin.time.Instant

/**
 * The text a visible signature drawn as [parameters] asks shows, as DSS draws it, or `null` when it shows its image
 * alone.
 *
 * A [VisibleSignatureParameters.text] that is not blank is shown as given, at DSS's default size. DSS draws nothing in
 * a box given neither text nor an image, which left such a signature invisible, so a box without either shows a label
 * instead: "Digitally signed by" and [signerName] on its first line, and "Date:" and [signingDate] on its second, as an
 * ISO 8601 date and time with the UTC offset of [timeZone]. The label fills the box when the box has a width and a
 * height, the only box DSS can fit text to, and is drawn at DSS's default size otherwise.
 *
 * The label is drawn in [font]. PDFBox refuses to draw a character its font has no glyph for, and the signing would
 * fail, so when [font] lacks a glyph for a character of [signerName], as DSS's own font lacks Greek, Chinese, Arabic
 * and Hebrew letters, the first line says only "Digitally signed".
 *
 * @param parameters The appearance the signature was asked for.
 * @param signerName The name the label gives the signer, the common name of the signing certificate's subject.
 * @param signingDate The signing time the signature claims.
 * @param font The font the label is drawn in, DSS's own unless another is given.
 * @param timeZone The time zone the label gives the date in, the system's unless another is given.
 */
internal fun visibleSignatureText(
	parameters: VisibleSignatureParameters,
	signerName: String,
	signingDate: Instant,
	font: DSSFileFont = DSSFileFont.initializeDefault(),
	timeZone: TimeZone = TimeZone.currentSystemDefault(),
): SignatureImageTextParameters? {
	val requested = parameters.text?.takeUnless { it.isBlank() }
	if (requested != null) return SignatureImageTextParameters().apply { text = requested }
	if (parameters.imagePath != null) return null
	val signedBy = if (font.hasGlyphsFor(signerName)) "Digitally signed by $signerName" else "Digitally signed"
	val date = InstantFormatter.formatDateTime(signingDate, timeZone, DateFormat.ISO_8601)
	return SignatureImageTextParameters().apply {
		this.font = font
		text = "$signedBy\nDate: $date"
		if (parameters.width > 0 && parameters.height > 0) textWrapping = TextWrapping.FILL_BOX
	}
}

/**
 * Whether this font has a glyph for every character of [text], looked up in the font's Unicode character map as PDFBox
 * looks a character up to draw it.
 */
private fun DSSFileFont.hasGlyphsFor(text: String): Boolean =
	inputStream.use { TTFParser().parse(RandomAccessReadBuffer(it)) }.use { trueType ->
		val characterMap = trueType.unicodeCmapLookup
		text.codePoints().allMatch { characterMap.getGlyphId(it) != 0 }
	}
