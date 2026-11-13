package cz.pizavo.omnisign.plugins

import io.ktor.server.application.*
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength

/**
 * Hold every request body the server receives to [maxBytes]: a larger one fails with Ktor's
 * [PayloadTooLargeException], which the status pages answer with `413 Payload Too Large`.
 *
 * A request whose `Content-Length` declares more fails as soon as a route receives its body,
 * before any of it is read. Every body is then handed to the route through a
 * [SizeLimitedByteReadChannel], which counts the bytes as the route reads them and fails once more
 * than [maxBytes] have arrived. That holds a body to the limit whether it declared its length or
 * not, as a chunked body, or an HTTP/2 one, need not. The exception keeps its type when it closes
 * another channel, so the multipart parser reading the body passes it on to the route unchanged.
 *
 * Nothing else bounds a body as a whole. Ktor's multipart parser reads each form field into
 * memory, so without this one request could hold many file-sized fields in the heap at once.
 * The channel runs no coroutine and reads only what the route asks for; the engine reads and
 * discards the rest of a refused or partly read HTTP/1.1 body, so that the connection can be reused.
 *
 * @param maxBytes The largest body, in bytes, that a request may carry.
 */
fun Application.configureRequestBodyLimit(maxBytes: Long) {
	install(createApplicationPlugin("RequestBodyLimit") {
		on(ReceiveRequestBytes) { call, body ->
			val declaredBytes = call.request.contentLength()
			if (declaredBytes != null && declaredBytes > maxBytes) {
				throw PayloadTooLargeException(maxBytes)
			}
			SizeLimitedByteReadChannel(body, maxBytes)
		}
	})
}
