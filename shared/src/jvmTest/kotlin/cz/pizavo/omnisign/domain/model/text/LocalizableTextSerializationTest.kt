package cz.pizavo.omnisign.domain.model.text

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Verifies that [LocalizableText] survives a JSON encode→decode round-trip, proving the
 * server↔web boundary: a server can serialize a domain error's text and the web client can
 * decode it and render it in the active locale (falling back to bundled English).
 */
class LocalizableTextSerializationTest : FunSpec({

	val json = Json {}

	test("Keyed round-trips through JSON and renders English from the decoded value") {
		val original: LocalizableText =
			LocalizableText.Keyed(MessageKey.ARCHIVING_FILE_NOT_FOUND, listOf("/x/y.pdf"))

		val decoded = json.decodeFromString<LocalizableText>(json.encodeToString(original))

		decoded shouldBe original
		decoded.english() shouldBe "File not found: /x/y.pdf"
	}

	test("Literal round-trips through JSON") {
		val original: LocalizableText = LocalizableText.Literal("boom")

		val decoded = json.decodeFromString<LocalizableText>(json.encodeToString(original))

		decoded shouldBe original
	}
})
