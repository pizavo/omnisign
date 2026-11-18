package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.parameters.VisibleSignatureParameters
import eu.europa.esig.dss.enumerations.TextWrapping
import eu.europa.esig.dss.pades.DSSFileFont
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Tests for [visibleSignatureText]: the text a visible signature shows, and the label a box given
 * neither text nor an image shows in its place.
 *
 * The dates are given in Prague's time zone, an hour ahead of UTC in November, so a label that
 * ignored the time zone, or gave the time in UTC, would show the wrong hour.
 */
class VisibleSignatureTextTest : FunSpec({

	val box = VisibleSignatureParameters(page = 1, x = 50f, y = 700f, width = 200f, height = 50f)
	val signingDate = Instant.parse("2026-11-18T16:45:12Z")
	val prague = TimeZone.of("Europe/Prague")

	/** The text [visibleSignatureText] gives a signature by [signer] drawn as [parameters] asks, at [signingDate] in Prague. */
	fun textFor(parameters: VisibleSignatureParameters, signer: String = "Jan Novák") =
		visibleSignatureText(parameters, signer, signingDate, timeZone = prague)

	test("a text that is not blank is shown as given, at DSS's default size") {
		val text = textFor(box.copy(text = "Signed for the faculty")).shouldNotBeNull()

		text.text shouldBe "Signed for the faculty"
		text.textWrapping shouldBe TextWrapping.FONT_BASED
	}

	test("a box with neither text nor an image names the signer and the signing time, filling the box") {
		val text = textFor(box).shouldNotBeNull()

		text.text shouldBe "Digitally signed by Jan Novák\nDate: 2026-11-18, 17:45:12 (+01:00)"
		text.textWrapping shouldBe TextWrapping.FILL_BOX
	}

	test("a blank text counts as none, so the box shows the label") {
		textFor(box.copy(text = " \t ")).shouldNotBeNull().text shouldBe
				"Digitally signed by Jan Novák\nDate: 2026-11-18, 17:45:12 (+01:00)"
	}

	test("an image without text is shown alone") {
		textFor(box.copy(imagePath = "logo.png")).shouldBeNull()
	}

	test("a blank text beside an image leaves the image alone") {
		textFor(box.copy(text = "", imagePath = "logo.png")).shouldBeNull()
	}

	test("a box without a width gets the label at DSS's default size, since DSS fits text only to a box of known size") {
		textFor(box.copy(width = 0f)).shouldNotBeNull().textWrapping shouldBe TextWrapping.FONT_BASED
	}

	test("a box without a height gets the label at DSS's default size, since DSS fits text only to a box of known size") {
		textFor(box.copy(height = 0f)).shouldNotBeNull().textWrapping shouldBe TextWrapping.FONT_BASED
	}

	test("a name in letters DSS's font cannot draw is left out of the label") {
		textFor(box, signer = "Γιάννης Παπαδόπουλος").shouldNotBeNull().text shouldBe
				"Digitally signed\nDate: 2026-11-18, 17:45:12 (+01:00)"
	}

	test("a single character the font cannot draw leaves the whole name out") {
		textFor(box, signer = "Jan Novák 李").shouldNotBeNull().text shouldBe
				"Digitally signed\nDate: 2026-11-18, 17:45:12 (+01:00)"
	}

	test("a name in the Latin and Cyrillic letters DSS's font has is kept") {
		textFor(box, signer = "Ľubomír Šťastný Иван").shouldNotBeNull().text shouldBe
				"Digitally signed by Ľubomír Šťastný Иван\nDate: 2026-11-18, 17:45:12 (+01:00)"
	}

	test("the label is drawn in the font its name was checked against") {
		val font = DSSFileFont.initializeDefault()

		visibleSignatureText(box, "Jan Novák", signingDate, font, prague).shouldNotBeNull().font shouldBeSameInstanceAs font
	}

	test("the label gives the date in the time zone it is given") {
		visibleSignatureText(box, "Jan Novák", signingDate, timeZone = TimeZone.UTC).shouldNotBeNull().text shouldBe
				"Digitally signed by Jan Novák\nDate: 2026-11-18, 16:45:12 (Z)"
	}
})
