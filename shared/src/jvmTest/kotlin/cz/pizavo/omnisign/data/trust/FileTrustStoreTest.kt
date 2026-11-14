package cz.pizavo.omnisign.data.trust

import cz.pizavo.omnisign.data.denyLookingUp
import cz.pizavo.omnisign.data.denyReading
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.fileAttributesViewOrNull
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Verifies [FileTrustStore]: content-addressed storage, per-scope references, per-reference type,
 * dedup, reference-counted GC, scope resolution, load-time repair, an index that cannot be read or
 * whose existence cannot be told, which every operation refuses while it leaves the directory as it
 * was, a missing index that is put back, a certificate file that cannot be read or looked up, which
 * is kept and can still be referenced, and the details of an error that an I/O failure causes.
 *
 * The tests of a file this user may not read or look up deny it through ACLs on Windows and
 * through the POSIX permissions elsewhere, and do not run as root, whom the permissions do not
 * stop. The tests of a certificate file whose existence cannot be told run only where ACLs can hide
 * one file: elsewhere that takes the search permission of its directory, which hides the index too.
 */
@OptIn(ExperimentalSerializationApi::class)
class FileTrustStoreTest : FunSpec({

	val root = tempdir().toPath()

	fun selfSigned(cn: String): X509Certificate {
		val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		val name = X500Name("CN=$cn,O=OmniSign Test")
		val now = System.currentTimeMillis()
		val builder = JcaX509v3CertificateBuilder(
			name,
			BigInteger.valueOf(now),
			Date(now - 1000L),
			Date(now + 365L * 24 * 60 * 60 * 1000),
			name,
			keyPair.public,
		)
		val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
		return JcaX509CertificateConverter().getCertificate(builder.build(signer))
	}

	fun der(cn: String): ByteArray = selfSigned(cn).encoded

	fun pem(der: ByteArray): ByteArray {
		val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
		return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n".toByteArray()
	}

	fun newDir(): Path = createTempDirectory(root, "store")

	fun newStore() = FileTrustStore(newDir())

	fun indexUnreadable(dir: Path): LocalizableText =
		LocalizableText.of(MessageKey.TRUSTSTORE_INDEX_UNREADABLE, dir.resolve("contents.cbor").toAbsolutePath().toString())

	fun contentsOf(dir: Path): Map<String, List<Byte>> =
		dir.listDirectoryEntries().associate { it.name to it.readBytes().toList() }

	fun cborText(text: String): ByteArray = byteArrayOf((0x60 + text.length).toByte()) + text.toByteArray()

	fun ByteArray.indicesOf(part: ByteArray): List<Int> =
		(0..size - part.size).filter { start -> part.indices.all { this[start + it] == part[it] } }

	fun ByteArray.replacingOnce(old: ByteArray, new: ByteArray): ByteArray {
		val at = indicesOf(old)
		at shouldHaveSize 1
		return copyOfRange(0, at.single()) + new + copyOfRange(at.single() + old.size, size)
	}

	test("add stores a content-addressed file and lists it") {
		val store = newStore()
		val added = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY).shouldBeRight()
		added.fingerprint shouldStartWith "sha256-"
		added.type shouldBe TrustedCertificateType.ANY
		store.list(TrustScope.Global).shouldBeRight().map { it.fingerprint } shouldBe listOf(added.fingerprint)
	}

	test("fingerprint is the algorithm-prefixed SHA-256 of the DER") {
		val store = newStore()
		val bytes = der("Root")
		val added = store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()
		val expected = "sha256-" + MessageDigest.getInstance("SHA-256").digest(bytes)
			.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
		added.fingerprint shouldBe expected
	}

	test("the same cert in two scopes is stored once and listed in each") {
		val store = newStore()
		val bytes = der("Shared")
		store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()
		val profile = TrustScope.Profile("p")
		store.add(profile, bytes, TrustedCertificateType.TSA).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 1
		store.list(profile).shouldBeRight() shouldHaveSize 1
		store.resolve(profile).shouldBeRight() shouldHaveSize 1
	}

	test("PEM and DER of the same cert deduplicate") {
		val store = newStore()
		val bytes = der("Root")
		val a = store.add(TrustScope.Global, bytes, TrustedCertificateType.ANY).shouldBeRight()
		val b = store.add(TrustScope.Global, pem(bytes), TrustedCertificateType.ANY).shouldBeRight()
		b.fingerprint shouldBe a.fingerprint
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 1
	}

	test("type is per reference: CA in global, TSA in a profile") {
		val store = newStore()
		val bytes = der("Shared")
		store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()
		val profile = TrustScope.Profile("p")
		store.add(profile, bytes, TrustedCertificateType.TSA).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight().single().type shouldBe TrustedCertificateType.CA
		store.list(profile).shouldBeRight().single().type shouldBe TrustedCertificateType.TSA
	}

	test("resolve unions global and the active scope, with the scope type overriding") {
		val store = newStore()
		val shared = der("Shared")
		store.add(TrustScope.Global, shared, TrustedCertificateType.CA).shouldBeRight()
		store.add(TrustScope.Global, der("GlobalOnly"), TrustedCertificateType.ANY).shouldBeRight()
		val profile = TrustScope.Profile("p")
		val sharedFp = store.add(profile, shared, TrustedCertificateType.TSA).shouldBeRight().fingerprint
		val anchors = store.resolve(profile).shouldBeRight()
		anchors shouldHaveSize 2
		anchors.single { it.fingerprint == sharedFp }.type shouldBe TrustedCertificateType.TSA
	}

	test("remove drops the reference and GCs the file when the last reference goes") {
		val dir = newDir()
		val store = FileTrustStore(dir)
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY).shouldBeRight().fingerprint
		store.remove(TrustScope.Global, fp).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
		dir.resolve("$fp.der").exists() shouldBe false
	}

	test("remove keeps the file while another scope still references it") {
		val dir = newDir()
		val store = FileTrustStore(dir)
		val bytes = der("Shared")
		val fp = store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		val profile = TrustScope.Profile("p")
		store.add(profile, bytes, TrustedCertificateType.TSA).shouldBeRight()
		store.remove(TrustScope.Global, fp).shouldBeRight()
		dir.resolve("$fp.der").exists() shouldBe true
		store.list(profile).shouldBeRight() shouldHaveSize 1
	}

	test("clearProfileScope removes the scope and GCs solely-referenced certs") {
		val dir = newDir()
		val store = FileTrustStore(dir)
		val profile = TrustScope.Profile("p")
		val fp = store.add(profile, der("ProfileOnly"), TrustedCertificateType.ANY).shouldBeRight().fingerprint
		store.clearProfileScope("p").shouldBeRight()
		store.list(profile).shouldBeRight() shouldHaveSize 0
		dir.resolve("$fp.der").exists() shouldBe false
	}

	test("setType changes the per-scope type") {
		val store = newStore()
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY).shouldBeRight().fingerprint
		store.setType(TrustScope.Global, fp, TrustedCertificateType.TSA).shouldBeRight()
		store.list(TrustScope.Global).shouldBeRight().single().type shouldBe TrustedCertificateType.TSA
	}

	test("a fresh store over the same directory repairs a dangling reference") {
		val dir = newDir()
		val fp = FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.ANY)
			.shouldBeRight().fingerprint
		dir.resolve("$fp.der").deleteExisting()
		FileTrustStore(dir).list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
	}

	test("a fresh store over a readable index deletes the certificate files no entry names") {
		val dir = newDir()
		FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight()
		val stray = dir.resolve("sha256-" + "0".repeat(64) + ".der")
		stray.writeBytes(der("Stray"))

		FileTrustStore(dir).list(TrustScope.Global).shouldBeRight() shouldHaveSize 1

		stray.exists() shouldBe false
	}

	test("an index that cannot be decoded fails every operation, leaves the directory as it was, and is read again by the next one") {
		val dir = newDir()
		val fp = FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.CA, source = "certs/root.pem")
			.shouldBeRight().fingerprint
		val index = dir.resolve("contents.cbor")
		val intact = index.readBytes()
		index.writeBytes(intact.copyOf(intact.size / 2))
		val before = contentsOf(dir)
		val store = FileTrustStore(dir)

		listOf(
			store.list(TrustScope.Global),
			store.resolve(TrustScope.Global),
			store.add(TrustScope.Global, der("Other"), TrustedCertificateType.ANY),
			store.remove(TrustScope.Global, fp),
			store.setType(TrustScope.Global, fp, TrustedCertificateType.TSA),
			store.reference(TrustScope.Profile("p"), fp, TrustedCertificateType.CA),
			store.clearProfileScope("p"),
			store.findBySource("certs/root.pem"),
			store.scopes(),
		).forEach { result ->
			val error = result.shouldBeLeft().shouldBeInstanceOf<TrustStoreError.StorageFailed>()
			error.text shouldBe indexUnreadable(dir)
			error.cause.shouldBeInstanceOf<SerializationException>()
		}
		contentsOf(dir) shouldBe before

		index.writeBytes(intact)
		store.list(TrustScope.Global).shouldBeRight().map { it.fingerprint } shouldBe listOf(fp)
	}

	test("an index naming a certificate type this build does not know, as a newer build may write, is left as it is") {
		val dir = newDir()
		FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.TSA).shouldBeRight()
		val index = dir.resolve("contents.cbor")
		index.writeBytes(index.readBytes().replacingOnce(cborText("TSA"), cborText("XYZ")))
		val before = contentsOf(dir)

		val error = FileTrustStore(dir).resolve(TrustScope.Global).shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.StorageFailed>()

		error.text shouldBe indexUnreadable(dir)
		contentsOf(dir) shouldBe before
	}

	test("an index in a format version this build does not read is left as it is") {
		val dir = newDir()
		FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight()
		val index = dir.resolve("contents.cbor")
		val cbor = Cbor { ignoreUnknownKeys = true }
		val written = cbor.decodeFromByteArray(TrustStoreIndex.serializer(), index.readBytes())
		written.version shouldBe TrustStoreIndex.CURRENT_VERSION
		index.writeBytes(cbor.encodeToByteArray(TrustStoreIndex.serializer(), written.copy(version = 2)))
		val before = contentsOf(dir)

		val error = FileTrustStore(dir).list(TrustScope.Global).shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.StorageFailed>()

		error.text shouldBe indexUnreadable(dir)
		error.details shouldBe "Format version 2; this build reads version 1"
		contentsOf(dir) shouldBe before
	}

	test("every index written carries its format version") {
		val dir = newDir()
		FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight()

		val versionEntry = cborText("version") + byteArrayOf(TrustStoreIndex.CURRENT_VERSION.toByte())
		dir.resolve("contents.cbor").readBytes().indicesOf(versionEntry) shouldHaveSize 1
	}

	test("an index this user may not read fails with the denied access as its details").config(
		enabledIf = { System.getProperty("user.name") != "root" },
	) {
		val dir = newDir()
		FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight()
		val before = contentsOf(dir)
		val restore = denyReading(dir.resolve("contents.cbor"))

		val error = try {
			FileTrustStore(dir).list(TrustScope.Global).shouldBeLeft()
		} finally {
			restore()
		}

		error.shouldBeInstanceOf<TrustStoreError.StorageFailed>()
		error.text shouldBe indexUnreadable(dir)
		error.details shouldBe "AccessDeniedException"
		contentsOf(dir) shouldBe before
	}

	test("an index whose existence cannot be told is one that cannot be read, not a missing one").config(
		enabledIf = { System.getProperty("user.name") != "root" },
	) {
		val dir = newDir()
		FileTrustStore(dir).add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight()
		val before = contentsOf(dir)
		val store = FileTrustStore(dir)
		val other = der("Other")
		val restore = denyLookingUp(dir.resolve("contents.cbor"))

		val results = try {
			listOf(
				store.list(TrustScope.Global),
				store.add(TrustScope.Global, other, TrustedCertificateType.CA),
			)
		} finally {
			restore()
		}

		results.forEach { result ->
			val error = result.shouldBeLeft().shouldBeInstanceOf<TrustStoreError.StorageFailed>()
			error.text shouldBe indexUnreadable(dir)
			error.details shouldBe "AccessDeniedException"
		}
		contentsOf(dir) shouldBe before
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 1
	}

	test("a store that found no index reads one put in place before its next operation rather than writing over it") {
		val dir = newDir()
		val store = FileTrustStore(dir)
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
		val restored = der("Restored")
		FileTrustStore(dir).add(TrustScope.Global, restored, TrustedCertificateType.CA).shouldBeRight()

		val added = der("Added")
		store.add(TrustScope.Global, added, TrustedCertificateType.CA).shouldBeRight()

		FileTrustStore(dir).resolve(TrustScope.Global).shouldBeRight().map { it.der.toList() } shouldBe
			listOf(restored.toList(), added.toList())
	}

	test("resolve leaves out a certificate file that cannot be read and gives the others").config(
		enabledIf = { System.getProperty("user.name") != "root" },
	) {
		val dir = newDir()
		val store = FileTrustStore(dir)
		val unreadable = store.add(TrustScope.Global, der("Unreadable"), TrustedCertificateType.CA)
			.shouldBeRight().fingerprint
		val readable = der("Readable")
		store.add(TrustScope.Global, readable, TrustedCertificateType.CA).shouldBeRight()
		val restore = denyReading(dir.resolve("$unreadable.der"))

		val resolved = try {
			store.resolve(TrustScope.Global).shouldBeRight()
		} finally {
			restore()
		}

		resolved.map { it.der.toList() } shouldBe listOf(readable.toList())
		store.resolve(TrustScope.Global).shouldBeRight() shouldHaveSize 2
	}

	test("adding a certificate again leaves its file as it is while the file cannot be read").config(
		enabledIf = { System.getProperty("user.name") != "root" },
	) {
		val dir = newDir()
		val bytes = der("Root")
		val fp = FileTrustStore(dir).add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		val damaged = byteArrayOf(1, 2, 3)
		dir.resolve("$fp.der").writeBytes(damaged)
		val restore = denyReading(dir.resolve("$fp.der"))

		val added = try {
			FileTrustStore(dir).add(TrustScope.Profile("work"), bytes, TrustedCertificateType.CA)
		} finally {
			restore()
		}

		added.shouldBeRight()
		dir.resolve("$fp.der").readBytes().toList() shouldBe damaged.toList()
	}

	test("an add or a change the file system refuses gives the class of the I/O failure as its details") {
		val dir = newDir()
		val store = FileTrustStore(dir)
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight().fingerprint
		dir.resolve("contents.cbor.tmp").createDirectory()

		listOf(
			store.add(TrustScope.Global, der("Other"), TrustedCertificateType.CA),
			store.setType(TrustScope.Global, fp, TrustedCertificateType.TSA),
		).forEach { result ->
			val error = result.shouldBeLeft().shouldBeInstanceOf<TrustStoreError.StorageFailed>()
			val cause = error.cause.shouldBeInstanceOf<IOException>()
			error.details shouldBe cause::class.simpleName
		}
	}

	test("a certificate file that cannot be read when the store loads is kept, not dropped as damaged").config(
		enabledIf = { System.getProperty("user.name") != "root" },
	) {
		val dir = newDir()
		val bytes = der("Root")
		val fp = FileTrustStore(dir).add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		val restore = denyReading(dir.resolve("$fp.der"))

		val listed = try {
			FileTrustStore(dir).list(TrustScope.Global).shouldBeRight()
		} finally {
			restore()
		}

		listed.map { it.fingerprint } shouldBe listOf(fp)
		FileTrustStore(dir).resolve(TrustScope.Global).shouldBeRight().single().der shouldBe bytes
	}

	test("a certificate file whose existence cannot be told when the store loads is kept, not dropped as missing").config(
		enabledIf = {
			System.getProperty("user.name") != "root" && root.fileAttributesViewOrNull<AclFileAttributeView>() != null
		},
	) {
		val dir = newDir()
		val bytes = der("Root")
		val fp = FileTrustStore(dir).add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		val restore = denyLookingUp(dir.resolve("$fp.der"))

		val listed = try {
			FileTrustStore(dir).list(TrustScope.Global).shouldBeRight()
		} finally {
			restore()
		}

		listed.map { it.fingerprint } shouldBe listOf(fp)
		FileTrustStore(dir).resolve(TrustScope.Global).shouldBeRight().single().der.toList() shouldBe bytes.toList()
	}

	test("without an index the store starts empty and keeps the certificate files it finds") {
		val dir = newDir()
		val bytes = der("Root")
		val fp = FileTrustStore(dir).add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		dir.resolve("contents.cbor").deleteExisting()

		val store = FileTrustStore(dir)
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
		dir.resolve("$fp.der").exists() shouldBe true

		store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()
		FileTrustStore(dir).resolve(TrustScope.Global).shouldBeRight().single().der shouldBe bytes
	}

	test("adding a certificate again replaces a damaged file left behind by an index that was moved away") {
		val dir = newDir()
		val bytes = der("Root")
		val fp = FileTrustStore(dir).add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		dir.resolve("contents.cbor").deleteExisting()
		dir.resolve("$fp.der").writeBytes(byteArrayOf(1, 2, 3))

		FileTrustStore(dir).add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight()

		dir.resolve("$fp.der").readBytes() shouldBe bytes
	}

	test("removing an unknown fingerprint returns NotFound") {
		val store = newStore()
		store.remove(TrustScope.Global, "sha256-deadbeef").shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.NotFound>()
	}

	test("reference adds an already-stored cert to another scope without its bytes") {
		val store = newStore()
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight().fingerprint
		store.reference(TrustScope.Profile("p"), fp, TrustedCertificateType.TSA).shouldBeRight()
		val referenced = store.list(TrustScope.Profile("p")).shouldBeRight().single()
		referenced.fingerprint shouldBe fp
		referenced.type shouldBe TrustedCertificateType.TSA
	}

	test("reference of an unknown fingerprint returns NotFound") {
		val store = newStore()
		store.reference(TrustScope.Global, "sha256-unknown", TrustedCertificateType.ANY).shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.NotFound>()
	}

	test("reference takes a stored copy whose existence cannot be told for one that is there, not a missing one").config(
		enabledIf = {
			System.getProperty("user.name") != "root" && root.fileAttributesViewOrNull<AclFileAttributeView>() != null
		},
	) {
		val dir = newDir()
		val bytes = der("Root")
		val store = FileTrustStore(dir)
		val fp = store.add(TrustScope.Global, bytes, TrustedCertificateType.CA).shouldBeRight().fingerprint
		val restore = denyLookingUp(dir.resolve("$fp.der"))

		val referenced = try {
			store.reference(TrustScope.Profile("p"), fp, TrustedCertificateType.TSA)
		} finally {
			restore()
		}

		referenced.shouldBeRight()
		val listed = store.list(TrustScope.Profile("p")).shouldBeRight().single()
		listed.fingerprint shouldBe fp
		listed.type shouldBe TrustedCertificateType.TSA
		FileTrustStore(dir).resolve(TrustScope.Profile("p")).shouldBeRight().single().der.toList() shouldBe
			bytes.toList()
	}

	test("findBySource resolves the fingerprint recorded for a source, and null otherwise") {
		val store = newStore()
		val fp = store.add(TrustScope.Global, der("Root"), TrustedCertificateType.CA, source = "certs/root.pem")
			.shouldBeRight().fingerprint
		store.findBySource("certs/root.pem").shouldBeRight() shouldBe fp
		store.findBySource("certs/absent.pem").shouldBeRight() shouldBe null
	}

	test("scopes lists every non-empty scope") {
		val store = newStore()
		store.add(TrustScope.Global, der("Root"), TrustedCertificateType.CA).shouldBeRight()
		store.add(TrustScope.Profile("p1"), der("P1"), TrustedCertificateType.ANY).shouldBeRight()
		store.scopes().shouldBeRight() shouldBe setOf(TrustScope.Global, TrustScope.Profile("p1"))
	}

	test("inspect parses a certificate without storing it") {
		val store = newStore()
		val parsed = store.inspect(der("Inspector")).shouldBeRight()
		parsed.fingerprint shouldStartWith "sha256-"
		store.list(TrustScope.Global).shouldBeRight() shouldHaveSize 0
	}

	test("inspect returns ParseFailed on non-certificate bytes") {
		val store = newStore()
		store.inspect(byteArrayOf(1, 2, 3)).shouldBeLeft()
			.shouldBeInstanceOf<TrustStoreError.ParseFailed>()
	}
})
