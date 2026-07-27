package cz.pizavo.omnisign.testing

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.content.OutgoingContent

/**
 * The outgoing request body decoded as text.
 *
 * Lets a spec assert on the `multipart/form-data` the repositories build without reimplementing the
 * multipart grammar: the decoded payload carries each part's `name="…"` disposition next to its
 * value, so a plain substring assertion is enough to pin down which fields were sent.
 */
suspend fun OutgoingContent.bodyText(): String = toByteArray().decodeToString()
