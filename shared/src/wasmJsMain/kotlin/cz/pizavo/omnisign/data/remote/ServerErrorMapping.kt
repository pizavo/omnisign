package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.api.model.responses.ApiError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

/** Tolerant reader for the server's [ApiError] error envelope. */
private val serverErrorJson = Json { ignoreUnknownKeys = true }

/**
 * The server's [ApiError.error] code from a failed request, or `null` when this is a network /
 * transport failure (there is no HTTP response) or the body is not a parseable [ApiError].
 *
 * Only the code is read; the server's `message` and `details` are deliberately discarded so that
 * neither the raw JSON envelope nor a low-level DSS reason ever reaches the user — those stay in the
 * server logs. Callers turn the code into a keyed, client-localized message via [serverErrorText],
 * falling back to their own generic remote-failure message when it returns `null`.
 */
suspend fun Throwable.serverErrorCode(): String? {
    val response = (this as? ResponseException)?.response ?: return null
    val body = runCatching { response.bodyAsText() }.getOrNull() ?: return null
    return runCatching { serverErrorJson.decodeFromString<ApiError>(body).error }.getOrNull()
}

/**
 * A keyed, client-localized message for a server rejection [code] that has no local-operation
 * analogue — the deliberate server-side gating rejections a user can act on. Returns `null` for every
 * other code (unclassified server or DSS failures, transport errors), signalling the caller to use its
 * own generic remote-failure message; either way the server's free-form text is never surfaced.
 */
fun serverErrorText(code: String?): LocalizableText? = when (code) {
    "INVALID_CONFIGURATION" -> LocalizableText.of(MessageKey.SERVER_INVALID_CONFIGURATION)
    "TIMESTAMP_NOT_ALLOWED" -> LocalizableText.of(MessageKey.SERVER_TIMESTAMP_NOT_ALLOWED)
    "CERTIFICATE_NOT_ALLOWED" -> LocalizableText.of(MessageKey.SERVER_CERTIFICATE_NOT_ALLOWED)
    else -> null
}

/**
 * Like `Either.mapLeft`, but [transform] may suspend — needed because turning a failed request into a
 * domain error reads the HTTP response body (a suspend call). The right value passes through untouched.
 */
suspend fun <A, B, C> Either<A, C>.mapLeftSuspend(transform: suspend (A) -> B): Either<B, C> =
    when (this) {
        is Either.Left -> transform(value).left()
        is Either.Right -> this
    }
