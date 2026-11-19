package cz.pizavo.omnisign.domain.model.text

/** How many characters of a text [shownInMessage] shows at most. */
private const val MAX_SHOWN_LENGTH = 64

/**
 * The categories of the characters [shownInMessage] writes as a `\u` escape: control characters,
 * such as the escape that starts a terminal's escape sequence and the line break; format
 * characters, such as the marks that set the direction of text; line and paragraph separators; and
 * surrogates, so that a text cut between the two halves of a character does not end in half of one.
 */
private val ESCAPED_CATEGORIES: Set<CharCategory> = setOf(
	CharCategory.CONTROL,
	CharCategory.FORMAT,
	CharCategory.LINE_SEPARATOR,
	CharCategory.PARAGRAPH_SEPARATOR,
	CharCategory.SURROGATE,
)

/**
 * [text], which a document or another file gives, as an error message shows it: cut to its first
 * [MAX_SHOWN_LENGTH] characters and ended with `...` where it is cut, then with each character of
 * the [ESCAPED_CATEGORIES] written as a `\u` escape and each backslash doubled, so that a text that
 * holds the text of an escape does not read like one that holds the character. The CLI prints
 * messages to a terminal, which would act on such a character, an escape sequence or a mark that
 * reverses the text after it, rather than show it, and a log would take a line break for the start
 * of a line of its own. The cut comes first, so that it counts the characters of the text and never
 * splits an escape.
 */
fun shownInMessage(text: String): String {
	val cut = if (text.length > MAX_SHOWN_LENGTH) text.take(MAX_SHOWN_LENGTH) + "..." else text
	return buildString {
		for (char in cut) {
			when {
				char == '\\' -> append("\\\\")
				char.category in ESCAPED_CATEGORIES -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
				else -> append(char)
			}
		}
	}
}
