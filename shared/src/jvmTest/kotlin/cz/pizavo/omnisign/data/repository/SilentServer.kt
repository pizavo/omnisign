package cz.pizavo.omnisign.data.repository

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A server on the loopback interface that accepts every connection and never answers, standing in
 * for a timestamp server, revocation responder or trusted-list host that hangs once the connection
 * is made. It holds each connection open until it is closed itself.
 *
 * [accepted] counts the connections it has taken, so a test can tell a request that waited on the
 * server from one that never reached it.
 */
class SilentServer : AutoCloseable {

	private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
	private val held = CopyOnWriteArrayList<Socket>()
	private val acceptedCount = AtomicInteger()

	/** The server's base URL, `http://127.0.0.1:<port>`. */
	val url: String = "http://$LOOPBACK:${socket.localPort}"

	/** How many connections the server has accepted so far. */
	val accepted: Int
		get() = acceptedCount.get()

	init {
		thread(isDaemon = true, name = "silent-server-${socket.localPort}") {
			while (!socket.isClosed) {
				runCatching { socket.accept() }.onSuccess {
					held += it
					acceptedCount.incrementAndGet()
				}
			}
		}
	}

	override fun close() {
		socket.close()
		held.forEach { runCatching { it.close() } }
	}

	companion object {
		/** The address the server listens on. */
		private const val LOOPBACK = "127.0.0.1"

		/** How many connections may wait to be accepted. */
		private const val BACKLOG = 50
	}
}
