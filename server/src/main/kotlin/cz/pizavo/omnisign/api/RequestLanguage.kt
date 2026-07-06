package cz.pizavo.omnisign.api

import io.ktor.server.application.*
import io.ktor.server.request.*

/**
 * The client's preferred response language, taken from the standard `Accept-Language` request
 * header: the highest-priority language tag the caller asked for, or `null` when the header is
 * absent, empty, or offers only the `*` wildcard.
 *
 * Reusable across routes — any handler that produces localizable output threads this into the
 * operation's parameters (currently the DSS validation report via
 * [cz.pizavo.omnisign.domain.model.parameters.ValidationParameters.language]; sign / timestamp
 * warnings can adopt it later with no new plumbing). The returned value is a raw BCP-47 tag (e.g.
 * `cs`, `sk`, `en`, `cs-CZ`); resolving it to a concrete message bundle — including the English
 * fallback for a language OmniSign ships no catalog for — happens downstream in the DSS layer, so
 * callers pass it through verbatim.
 *
 * @return The top-preference language tag, or `null` when the client expressed no usable preference.
 */
fun ApplicationCall.preferredLanguageTag(): String? =
	request.acceptLanguageItems()
		.map { it.value.trim() }
		.firstOrNull { it.isNotEmpty() && it != "*" }
