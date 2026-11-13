package cz.pizavo.omnisign.api.exception

/**
 * Thrown when a request's multipart body cannot be read: its `Content-Type` names no boundary or
 * one that RFC 2046 does not allow, a boundary line carries more than the boundary, or the body ends
 * inside a part.
 *
 * Caught by the StatusPages plugin and mapped to HTTP 400 Bad Request.
 *
 * @param message What is wrong with the body, which the response passes on to the client.
 */
class MalformedMultipartException(message: String) : RuntimeException(message)
