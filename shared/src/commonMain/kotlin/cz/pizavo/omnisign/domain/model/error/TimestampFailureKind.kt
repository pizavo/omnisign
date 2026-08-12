package cz.pizavo.omnisign.domain.model.error

/**
 * Why a timestamp request failed, as carried by [ArchivingError.TimestampFailed].
 *
 * A classification rather than a single "is it worth trying again" flag, because two of the kinds are
 * equivalent to a batch but call for opposite responses from whoever has to fix them: an unreachable
 * server is an outage to wait out, while one that answers without producing a timestamp usually means
 * the configured URL is not an RFC 3161 endpoint. A boolean would answer the first question and lose
 * the second.
 */
enum class TimestampFailureKind {

    /**
     * The request never reached the server — a connect or read timeout, a refused or reset
     * connection, an unresolvable host, or a TLS failure.
     */
    UNREACHABLE,

    /**
     * The server answered, but with something that is not a timestamp token: an error page from a
     * reverse proxy, a truncated stream, or an endpoint that does not speak RFC 3161.
     */
    MALFORMED_RESPONSE,

    /**
     * The server answered and refused this particular request, typically with an RFC 3161
     * `PKIFailureInfo` code.
     *
     * Also the fallback for a timestamp failure that could not be classified further. That is the safe
     * default, being the one kind that does not stop a batch from calling the server again.
     */
    REJECTED,
}

/**
 * Whether this failure is a property of the server rather than of one request, so the next document
 * would meet it identically.
 *
 * A batch uses this to decide whether calling the same server again is worth anything.
 * [TimestampFailureKind.REJECTED] is excluded: the server is working and declining this request,
 * which says nothing about the next one.
 */
val TimestampFailureKind.isServerWide: Boolean
    get() = this == TimestampFailureKind.UNREACHABLE || this == TimestampFailureKind.MALFORMED_RESPONSE
