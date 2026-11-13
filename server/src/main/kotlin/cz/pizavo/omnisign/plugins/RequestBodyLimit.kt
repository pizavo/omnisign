package cz.pizavo.omnisign.plugins

import io.ktor.server.application.*
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.writer

/**
 * Hold every request body the server receives to [maxBytes]: a larger one fails with Ktor's
 * [PayloadTooLargeException], which the status pages answer with `413 Payload Too Large`.
 *
 * A request whose `Content-Length` declares more fails as soon as a route receives its body,
 * before any of it is read. A body without a declared length (chunked, or HTTP/2 without a
 * content-length) is handed on through a channel that fails once more than [maxBytes] bytes have
 * passed through it. The exception keeps its type when a reader of that channel raises it, so the
 * multipart parser reading the body raises it to the route too.
 *
 * Nothing else bounds a body as a whole. Ktor's multipart parser reads each form field into
 * memory up to its per-part limit, which the upload routes raise to fit their file, so without
 * this one request could hold many file-sized fields in the heap at once. The engine still reads
 * and discards the rest of a refused HTTP/1.1 body, so that the connection can be reused.
 *
 * @param maxBytes The largest body, in bytes, that a request may carry.
 */
fun Application.configureRequestBodyLimit(maxBytes: Long) {
	val probeBytes = maxBytes.coerceAtMost(Long.MAX_VALUE - 1) + 1
	install(createApplicationPlugin("RequestBodyLimit") {
		on(ReceiveRequestBytes) { call, body ->
			val declaredBytes = call.request.contentLength()
			if (declaredBytes != null && declaredBytes > maxBytes) {
				throw PayloadTooLargeException(maxBytes)
			}
			call.writer {
				if (body.copyTo(channel, probeBytes) > maxBytes) {
					throw PayloadTooLargeException(maxBytes)
				}
			}.channel
		}
	})
}
