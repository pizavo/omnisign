package cz.pizavo.omnisign.domain.model.error

import cz.pizavo.omnisign.domain.model.text.LocalizableText

/**
 * The localizable text for this error: its own [LocalizableError.text] when it is localizable,
 * otherwise its pre-rendered English [OperationError.message] wrapped as a [LocalizableText.Literal].
 *
 * Lets a UI carry every domain error as a [LocalizableText] uniformly, whether or not the error
 * has been migrated to [LocalizableError] yet.
 */
fun OperationError.localizableText(): LocalizableText =
	(this as? LocalizableError)?.text ?: LocalizableText.Literal(message)
