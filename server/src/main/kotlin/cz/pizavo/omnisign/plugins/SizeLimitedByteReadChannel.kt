package cz.pizavo.omnisign.plugins

import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.io.Buffer

/**
 * A [ByteReadChannel] that passes on [delegate]'s bytes until more than [maxBytes] of them have
 * arrived, and from then on fails every read with [PayloadTooLargeException].
 *
 * It takes bytes from [delegate] only when its reader asks for them, and counts them as it does, so
 * it runs no coroutine of its own and leaves the rest of [delegate] unread once its reader stops.
 * The engine reads and discards that rest after the call, to reuse the connection, which is also why
 * exceeding the limit fails only this channel and leaves [delegate] open.
 *
 * Cancelling this channel cancels [delegate] too, as cancelling the engine's channel would without
 * the limit, and the engine then reads none of the rest. Ktor's `copyTo` cancels the channel it reads
 * from when a copy fails, and the multipart parser copies a part that declares its own length with
 * it, so the rest of a body that exceeds the limit inside such a part, or whose route stops reading
 * one, is left unread.
 *
 * It follows ktor-io's [io.ktor.utils.io.CountedByteReadChannel], which counts bytes the same way,
 * and fails the way ktor-io's own channels do once closed with a cause: [readBuffer] and
 * [awaitContent] throw the failure, and [isClosedForRead] and [closedCause] report it.
 *
 * @param delegate The request body as the engine receives it.
 * @param maxBytes The most bytes that [delegate] may deliver.
 */
internal class SizeLimitedByteReadChannel(
	private val delegate: ByteReadChannel,
	private val maxBytes: Long,
) : ByteReadChannel {

	private val buffer = Buffer()

	private var received = 0L

	private var failure: PayloadTooLargeException? = null

	override val closedCause: Throwable?
		get() = failure ?: delegate.closedCause

	override val isClosedForRead: Boolean
		get() = failure != null || (buffer.exhausted() && delegate.isClosedForRead)

	@InternalAPI
	override val readBuffer: Buffer
		get() {
			transferFromDelegate()
			return buffer
		}

	@OptIn(InternalAPI::class)
	override suspend fun awaitContent(min: Int): Boolean {
		if (readBuffer.size >= min) {
			return true
		}
		if (delegate.awaitContent(min)) {
			transferFromDelegate()
			return true
		}
		return false
	}

	override fun cancel(cause: Throwable?) {
		delegate.cancel(cause)
		buffer.close()
	}

	/**
	 * Move whatever [delegate] has buffered into [buffer], counting it, and fail once more than
	 * [maxBytes] have arrived in all.
	 *
	 * @throws PayloadTooLargeException If more than [maxBytes] have arrived, now or before.
	 */
	@OptIn(InternalAPI::class)
	private fun transferFromDelegate() {
		failure?.let { throw it }
		received += buffer.transferFrom(delegate.readBuffer)
		if (received > maxBytes) {
			buffer.clear()
			throw PayloadTooLargeException(maxBytes).also { failure = it }
		}
	}
}
