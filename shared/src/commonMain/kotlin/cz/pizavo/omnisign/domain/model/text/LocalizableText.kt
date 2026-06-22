package cz.pizavo.omnisign.domain.model.text

import kotlinx.serialization.Serializable

/**
 * A user-facing piece of text in locale-independent form.
 *
 * Either a [Keyed] message — a [MessageKey] plus positional [Keyed.args] that any frontend
 * can render in its own locale — or a [Literal] verbatim string for text that is not (yet)
 * translatable, such as propagated library/exception text or messages not worth a key.
 *
 * [english] yields the default English rendering: the single source the CLI, the server, and
 * logs use directly, and the fallback a localizing frontend uses when it has no translation
 * for a key. The type is serializable, so a server can hand it to the web client and let the
 * client translate.
 */
@Serializable
sealed interface LocalizableText {

	/** The default English rendering of this text. */
	fun english(): String

	/**
	 * A translatable message identified by [key], with positional [args] substituted into the
	 * template's `%1$s`, `%2$s`, … placeholders (in any order the target language needs).
	 */
	@Serializable
	data class Keyed(val key: MessageKey, val args: List<String> = emptyList()) : LocalizableText {
		override fun english(): String = EnglishMessages.render(key, args)
	}

	/** A verbatim, non-translatable string — e.g. propagated text, or text not worth a key. */
	@Serializable
	data class Literal(val value: String) : LocalizableText {
		override fun english(): String = value
	}

	companion object {
		/** Build a [Keyed] message from a [key] and its positional [args]. */
		fun of(key: MessageKey, vararg args: String): Keyed = Keyed(key, args.toList())
	}
}
