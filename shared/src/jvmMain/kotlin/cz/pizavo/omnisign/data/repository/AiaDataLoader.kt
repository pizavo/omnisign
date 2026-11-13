package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.CommonsHttpClientResponseHandler
import eu.europa.esig.dss.spi.client.http.DataLoader
import eu.europa.esig.dss.spi.exception.DSSExternalResourceException
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.io.HttpClientConnectionManager
import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [DataLoader] behind the [DefaultAIASource] of every certificate verifier [DssServiceFactory]
 * builds. DSS uses it to download a certificate's issuer from the URL in the certificate's Authority
 * Information Access extension when it cannot find that issuer in the document or among the trusted
 * certificates.
 *
 * Those URLs come from the certificates in the document being processed, so from whoever made the
 * document, and DSS follows them before it knows whether the chain is trusted. Every download is
 * therefore held to limits that a hostile URL cannot get around:
 * - Only `http` and `https` URLs are fetched; `file`, `ftp`, `ldap` and any other scheme is refused.
 * - [timeoutMillis] bounds connecting, each read, and the whole download, so a server that answers
 *   a byte at a time cannot hold the caller for longer than that.
 * - A body larger than [maxResponseBytes] ends the download, without reading the rest.
 * - Unless [allowPrivateAddresses] is set, a host is reached only on its public addresses (see
 *   [PublicAddressDnsResolver]), which holds for every redirect as well.
 *
 * The loader only downloads: [post] is refused.
 *
 * @param timeoutMillis Limit, in milliseconds, on connecting, on each read and on the whole download.
 * @param allowPrivateAddresses Whether a download may reach loopback, private-network, link-local
 *   and other non-public addresses. The desktop app and the CLI allow them, so an issuer published
 *   on an internal network can still be found; the server does not, unless its `server.yml` says so.
 * @param maxResponseBytes Largest body accepted. An issuer certificate, or a PKCS#7 bundle of a few,
 *   takes a few kilobytes.
 */
class AiaDataLoader(
	timeoutMillis: Int,
	allowPrivateAddresses: Boolean,
	maxResponseBytes: Int = MAX_RESPONSE_BYTES,
) : DataLoader {

	private val http = BoundedHttpLoader(timeoutMillis, allowPrivateAddresses, maxResponseBytes)

	/**
	 * Download [url] within the limits described on the class.
	 *
	 * @throws DSSExternalResourceException When [url] is not an `http` or `https` URL, the download
	 *   fails, or it breaks a limit.
	 */
	override fun get(url: String): ByteArray = http.get(url)

	/**
	 * Download the first of [urlStrings] that answers with content, trying them in order, each within
	 * the limits described on the class.
	 *
	 * @throws eu.europa.esig.dss.spi.exception.DSSDataLoaderMultipleException When none does, with the
	 *   failure of each URL.
	 */
	override fun get(urlStrings: List<String>): DataLoader.DataAndUrl = http.get(urlStrings)

	/** Always refused: issuer certificates are only ever downloaded. */
	override fun post(url: String, content: ByteArray): ByteArray =
		throw UnsupportedOperationException("The AIA data loader only downloads issuer certificates")

	/** Ignored: a download sends no body, so there is no content type to set. */
	override fun setContentType(contentType: String?) = Unit

	companion object {

		/** Default [maxResponseBytes]: 1 MiB, a few hundred times the size of a typical issuer bundle. */
		const val MAX_RESPONSE_BYTES = 1024 * 1024

		/** Size of each read from a response body. */
		private const val READ_CHUNK_BYTES = 8 * 1024

		/** Whether [url] parses as an absolute `http` or `https` URL. */
		private fun isHttpUrl(url: String): Boolean {
			val scheme = try {
				URI(url.trim()).scheme
			} catch (_: URISyntaxException) {
				null
			}
			return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
		}
	}

	/**
	 * The [CommonsDataLoader] that does the downloading for [AiaDataLoader]. It keeps DSS's own HTTP
	 * client set-up (TLS, request configuration, redirects) and adds the limits: it replaces
	 * [CommonsDataLoader.get] for single URLs and installs a [PublicAddressDnsResolver] in the
	 * connection manager when private addresses are not allowed. [CommonsDataLoader.get] for a list
	 * of URLs ends up in the replaced method for each one.
	 */
	private class BoundedHttpLoader(
		private val timeoutMillis: Int,
		private val allowPrivateAddresses: Boolean,
		private val maxResponseBytes: Int,
	) : CommonsDataLoader() {

		init {
			timeoutConnection = timeoutMillis
			timeoutConnectionRequest = timeoutMillis
			timeoutResponse = timeoutMillis
			timeoutSocket = timeoutMillis
		}

		/**
		 * Download [urlString], refusing anything but `http` and `https`. A watchdog cancels the
		 * request once [timeoutMillis] have passed, which closes its connection and so also ends a
		 * read that is still waiting for data.
		 *
		 * @throws DSSExternalResourceException When the URL is refused, the download fails, it takes
		 *   too long, or its body is too large.
		 */
		override fun get(urlString: String): ByteArray {
			if (!isHttpUrl(urlString)) {
				throw DSSExternalResourceException(
					"Refused to download '$urlString': issuer certificates are downloaded only over http and https"
				)
			}
			var request: HttpGet? = null
			var client: CloseableHttpClient? = null
			val watchdog = CompletableFuture<Unit>()
			val timedOut = AtomicBoolean(false)
			try {
				val httpRequest = getHttpRequest(urlString).also { request = it }
				client = getHttpClient(urlString)
				watchdog.orTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS).whenComplete { _, error ->
					if (error is TimeoutException) {
						timedOut.set(true)
						httpRequest.cancel()
					}
				}
				val target = getHttpHost(httpRequest)
				return client.execute(
					target,
					httpRequest,
					getHttpContext(target),
					CappedResponseHandler(httpRequest, maxResponseBytes),
				)
			} catch (e: IOException) {
				val reason = if (timedOut.get()) "it took longer than $timeoutMillis ms" else e.message ?: e.javaClass.simpleName
				throw DSSExternalResourceException("Unable to download '$urlString': $reason", e)
			} catch (e: URISyntaxException) {
				throw DSSExternalResourceException("Unable to download '$urlString': ${e.message}", e)
			} finally {
				watchdog.complete(Unit)
				closeQuietly(request, client)
			}
		}

		/**
		 * The connection manager [CommonsDataLoader] builds, with a [PublicAddressDnsResolver] added
		 * when private addresses are not allowed.
		 */
		override fun getConnectionManager(): HttpClientConnectionManager {
			val builder = PoolingHttpClientConnectionManagerBuilder.create()
				.setTlsSocketStrategy(tlsSocketStrategy)
				.setDefaultSocketConfig(socketConfig)
				.setMaxConnTotal(connectionsMaxTotal)
				.setMaxConnPerRoute(connectionsMaxPerRoute)
				.setDefaultConnectionConfig(
					ConnectionConfig.custom()
						.setConnectTimeout(Timeout.ofMilliseconds(timeoutConnection.toLong()))
						.setTimeToLive(TimeValue.ofMilliseconds(connectionTimeToLive.toLong()))
						.build()
				)
			if (!allowPrivateAddresses) {
				builder.setDnsResolver(PublicAddressDnsResolver())
			}
			return builder.build()
		}
	}

	/**
	 * Response handler that accepts what DSS's own accepts, a `200 OK`, and reads at most [maxBytes]
	 * of its body.
	 *
	 * A longer body cancels [request] before the error is raised. Closing the body stream would
	 * otherwise read the rest of it, so a server sending without end would hold the caller after all.
	 */
	private class CappedResponseHandler(
		private val request: HttpUriRequestBase,
		private val maxBytes: Int,
	) : CommonsHttpClientResponseHandler() {

		/**
		 * Read the body of [responseEntity] up to [maxBytes].
		 *
		 * @throws IOException When the body is larger, or reading it fails.
		 */
		override fun getContent(responseEntity: HttpEntity): ByteArray {
			if (responseEntity.contentLength > maxBytes) tooLarge()
			val body = ByteArrayOutputStream()
			val chunk = ByteArray(READ_CHUNK_BYTES)
			responseEntity.content.use { stream ->
				var read = stream.read(chunk)
				while (read >= 0) {
					if (body.size() + read > maxBytes) tooLarge()
					body.write(chunk, 0, read)
					read = stream.read(chunk)
				}
			}
			return body.toByteArray()
		}

		/** Cancel [request], so the rest of the body is never read, and fail. */
		private fun tooLarge(): Nothing {
			request.cancel()
			throw IOException("the response is larger than $maxBytes bytes")
		}
	}
}
