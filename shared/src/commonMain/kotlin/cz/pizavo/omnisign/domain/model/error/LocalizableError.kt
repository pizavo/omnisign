package cz.pizavo.omnisign.domain.model.error

import cz.pizavo.omnisign.domain.model.text.LocalizableText

/**
 * Capability for an error that carries a localizable, locale-independent [text] instead of a
 * pre-rendered English string.
 *
 * Frontends that localize (composeApp on desktop and web) check `is LocalizableError` and resolve
 * [text] to the active locale; everyone else — the CLI, the server, logs — reads the owner's
 * derived English [OperationError.message].
 *
 * This is deliberately an orthogonal capability rather than a subtype of [OperationError], so the
 * sealed error hierarchy and its exhaustive `when`s are unaffected by which errors are localizable.
 * An implementor that is also an [OperationError] derives its `message` from [text] (one line),
 * keeping a single source of truth and avoiding drift between the key and a hand-written line.
 */
interface LocalizableError {

	/** The localizable, locale-independent text for this error. */
	val text: LocalizableText
}
