package cz.pizavo.omnisign.e2e

import com.sun.net.httpserver.HttpServer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TimeStampRequest
import org.bouncycastle.tsp.TimeStampResponseGenerator
import org.bouncycastle.tsp.TimeStampTokenGenerator
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal in-process RFC 3161 timestamp authority for end-to-end tests, mirroring the local test
 * TSA used during development.
 *
 * Loads a CA-issued TSA key and certificate chain from a PKCS#12 (DSS rejects purely self-signed
 * timestamp chains for LT/LTA) and serves RFC 3161 responses over loopback HTTP on an ephemeral
 * port, so the signing/archiving pipeline exercises the real `OnlineTSPSource` HTTP path fully
 * offline. Close it (it is [AutoCloseable]) to stop the server.
 */
class LocalTestTsa private constructor(private val server: HttpServer) : AutoCloseable {

	/** The loopback URL a DSS `OnlineTSPSource` should target. */
	val url: String = "http://127.0.0.1:${server.address.port}/"

	override fun close() = server.stop(0)

	companion object {
		/**
		 * Start a TSA backed by [p12Bytes] (PKCS#12, password [password]) on an ephemeral loopback port.
		 *
		 * @param genTime Supplies the time each issued token asserts. Defaults to the wall clock;
		 *   override it to date tokens in the past, which is the only way a test can build a document
		 *   that was timestamped while its signing certificate was still valid and has since expired.
		 *   The TSA certificate must itself be valid at whatever time this returns.
		 */
		fun start(
			p12Bytes: ByteArray,
			password: String = "test1234",
			genTime: () -> Date = { Date() },
		): LocalTestTsa {
			Security.addProvider(BouncyCastleProvider())
			val keyStore = KeyStore.getInstance("PKCS12")
			p12Bytes.inputStream().use { keyStore.load(it, password.toCharArray()) }
			val alias = keyStore.aliases().nextElement()
			val key = keyStore.getKey(alias, password.toCharArray()) as PrivateKey
			val chain = keyStore.getCertificateChain(alias).map { it as X509Certificate }
			val tsaCert = chain.first()

			val digestProvider = JcaDigestCalculatorProviderBuilder().setProvider("BC").build()
			val sha256 = digestProvider.get(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256))
			val tokenGenerator = TimeStampTokenGenerator(
				JcaSimpleSignerInfoGeneratorBuilder().setProvider("BC").build("SHA256withRSA", key, tsaCert),
				sha256,
				ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"),
			).apply { addCertificates(JcaCertStore(chain)) }
			val responseGenerator = TimeStampResponseGenerator(tokenGenerator, TSPAlgorithms.ALLOWED)

			val counter = AtomicLong()
			val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			server.createContext("/") { exchange ->
				try {
					val request = TimeStampRequest(exchange.requestBody.readAllBytes())
					val response =
						responseGenerator.generate(request, BigInteger.valueOf(counter.incrementAndGet()), genTime())
					val bytes = response.encoded
					exchange.responseHeaders.set("Content-Type", "application/timestamp-reply")
					exchange.sendResponseHeaders(200, bytes.size.toLong())
					exchange.responseBody.use { it.write(bytes) }
				} catch (e: Exception) {
					runCatching { exchange.sendResponseHeaders(500, -1) }
				} finally {
					exchange.close()
				}
			}
			server.executor = null
			server.start()
			return LocalTestTsa(server)
		}
	}
}
