package cz.pizavo.omnisign.ui.layout

import cz.pizavo.omnisign.ui.model.GlobChip
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Verifies [parseGlobChips] splits on `,`/`;`, rejects non-absolute globs and bare directories,
 * flags missing target directories and non-PDF-extension globs, and de-duplicates against existing
 * chips.
 */
class GlobChipParsingTest : FunSpec({

	val existingDir = tempdir().toPath().toString()
	val missingDir = "$existingDir/does-not-exist-xyz"
	val existingFile = java.io.File(existingDir, "report.pdf").apply { writeText("x") }.path.replace('\\', '/')

	test("accepts absolute globs, splitting on both comma and semicolon") {
		val (chips, invalid) = parseGlobChips(
			"$existingDir/*.pdf, $existingDir/a/*.pdf; $existingDir/b/*.pdf",
			emptyList(),
		)
		chips.map { it.glob } shouldContainExactly listOf(
			"$existingDir/*.pdf",
			"$existingDir/a/*.pdf",
			"$existingDir/b/*.pdf",
		)
		invalid.shouldBeEmpty()
	}

	test("rejects non-absolute globs as invalid, keeping absolute ones") {
		val (chips, invalid) = parseGlobChips("docs/*.pdf, $existingDir/*.pdf", emptyList())
		chips.map { it.glob } shouldContainExactly listOf("$existingDir/*.pdf")
		invalid shouldContainExactly listOf("docs/*.pdf")
	}

	test("flags a missing target directory but still accepts the glob") {
		val (chips, _) = parseGlobChips("$existingDir/*.pdf, $missingDir/*.pdf", emptyList())
		chips.single { it.glob == "$existingDir/*.pdf" }.warning.shouldBeNull()
		chips.single { it.glob == "$missingDir/*.pdf" }.warning.shouldNotBeNull()
	}

	test("de-duplicates against existing chips") {
		val existing = listOf(GlobChip("$existingDir/*.pdf", warning = null))
		val (chips, invalid) = parseGlobChips("$existingDir/*.pdf, $existingDir/c/*.pdf", existing)
		chips.map { it.glob } shouldContainExactly listOf("$existingDir/*.pdf", "$existingDir/c/*.pdf")
		invalid.shouldBeEmpty()
	}

	test("rejects a bare existing directory without a file pattern") {
		val (chips, invalid) = parseGlobChips("$existingDir, $existingDir/*.pdf", emptyList())
		chips.map { it.glob } shouldContainExactly listOf("$existingDir/*.pdf")
		invalid shouldContainExactly listOf(existingDir)
	}

	test("rejects a non-existent bare path without a file pattern") {
		val (chips, invalid) = parseGlobChips(missingDir, emptyList())
		chips.shouldBeEmpty()
		invalid shouldContainExactly listOf(missingDir)
	}

	test("accepts a literal existing file, as produced by the file picker") {
		val (chips, invalid) = addGlobChips(listOf(existingFile), emptyList())
		chips.single().glob shouldBe existingFile
		chips.single().warning.shouldBeNull()
		invalid.shouldBeEmpty()
	}

	test("flags a glob that targets a non-PDF extension but still accepts it") {
		val (chips, invalid) = parseGlobChips("$existingDir/*.xml", emptyList())
		chips.single().warning.shouldNotBeNull()
		invalid.shouldBeEmpty()
	}

	test("globTargetsNonPdf flags only concrete non-PDF extensions") {
		globTargetsNonPdf("C:/Docs/*.xml").shouldBeTrue()
		globTargetsNonPdf("C:/Docs/notes.txt").shouldBeTrue()
		globTargetsNonPdf("C:/Docs/*.pdf").shouldBeFalse()
		globTargetsNonPdf("C:/Docs/*.PDF").shouldBeFalse()
		globTargetsNonPdf("C:/Docs/*").shouldBeFalse()
		globTargetsNonPdf("C:/Docs/report-*").shouldBeFalse()
		globTargetsNonPdf("C:/Docs/*.{pdf,xml}").shouldBeFalse()
	}
})
