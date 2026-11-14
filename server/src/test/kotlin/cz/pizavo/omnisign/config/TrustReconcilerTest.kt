package cz.pizavo.omnisign.config

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.time.Instant

/**
 * Verifies [TrustReconciler] imports declared references, checks the fingerprint pin before it
 * stores a certificate, decodes wrapped inline Base64, references a stored copy when the source is
 * gone and reports why that fails, clears scopes the configuration drops, and rejects malformed
 * entries. One test runs it on a real [FileTrustStore] whose index cannot be read.
 */
class TrustReconcilerTest : FunSpec({

	val trustStore: TrustStore = mockk()
	val reconciler = TrustReconciler(trustStore)
	val root = tempdir().toPath()

	beforeTest { clearMocks(trustStore) }

	fun anchor(fingerprint: String) = TrustedCertificate(
		fingerprint = fingerprint,
		subjectDN = "CN=Test",
		notBefore = Instant.fromEpochMilliseconds(0),
		notAfter = Instant.fromEpochMilliseconds(0),
		type = TrustedCertificateType.CA,
	)

	fun globalConfig(vararg refs: TrustedCertificateRef) =
		AppConfig(global = GlobalConfig(validation = ValidationConfig(trustedCertificates = refs.toList())))

	fun newDir() = createTempDirectory(root, "reconcile")

	test("imports a path-based reference into the global scope") {
		val dir = newDir()
		dir.resolve("ca.pem").writeBytes(byteArrayOf(1, 2, 3))
		coEvery { trustStore.add(any(), any(), any(), any()) } returns anchor("sha256-aa").right()
		coEvery { trustStore.scopes() } returns setOf(TrustScope.Global).right()
		coEvery { trustStore.list(TrustScope.Global) } returns listOf(anchor("sha256-aa")).right()

		reconciler.reconcile(globalConfig(TrustedCertificateRef(path = "ca.pem", type = TrustedCertificateType.CA)), dir)

		coVerify { trustStore.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem") }
	}

	test("a fingerprint pin mismatch aborts startup before the certificate is stored") {
		val dir = newDir()
		dir.resolve("ca.pem").writeBytes(byteArrayOf(1, 2, 3))
		coEvery { trustStore.inspect(any()) } returns anchor("sha256-real").right()

		val failure = shouldThrow<IllegalStateException> {
			reconciler.reconcile(
				globalConfig(
					TrustedCertificateRef(path = "ca.pem", type = TrustedCertificateType.CA, fingerprint = "sha256-pinned"),
				),
				dir,
			)
		}

		failure.message shouldContain "declared fingerprint sha256-pinned but the certificate is sha256-real"
		coVerify(exactly = 0) { trustStore.add(any(), any(), any(), any()) }
	}

	test("a certificate that matches its pin is imported") {
		val dir = newDir()
		dir.resolve("ca.pem").writeBytes(byteArrayOf(1, 2, 3))
		coEvery { trustStore.inspect(any()) } returns anchor("sha256-pin").right()
		coEvery { trustStore.add(any(), any(), any(), any()) } returns anchor("sha256-pin").right()
		coEvery { trustStore.scopes() } returns setOf(TrustScope.Global).right()
		coEvery { trustStore.list(TrustScope.Global) } returns listOf(anchor("sha256-pin")).right()

		reconciler.reconcile(
			globalConfig(TrustedCertificateRef(path = "ca.pem", type = TrustedCertificateType.CA, fingerprint = "sha256-pin")),
			dir,
		)

		coVerify { trustStore.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem") }
	}

	test("an inline certificate wrapped over several lines, as a YAML block holds a PEM body, is decoded") {
		val dir = newDir()
		var imported: ByteArray? = null
		coEvery { trustStore.add(any(), any(), any(), any()) } answers {
			imported = secondArg()
			anchor("sha256-inline").right()
		}
		coEvery { trustStore.scopes() } returns setOf(TrustScope.Global).right()
		coEvery { trustStore.list(TrustScope.Global) } returns listOf(anchor("sha256-inline")).right()

		reconciler.reconcile(
			globalConfig(TrustedCertificateRef(inline = "AQID\n  BAU\r\n", type = TrustedCertificateType.CA)),
			dir,
		)

		imported?.toList() shouldBe listOf<Byte>(1, 2, 3, 4, 5)
	}

	test("inline Base64 that does not decode aborts startup") {
		val dir = newDir()

		val failure = shouldThrow<IllegalStateException> {
			reconciler.reconcile(globalConfig(TrustedCertificateRef(inline = "AQ*D", type = TrustedCertificateType.CA)), dir)
		}

		failure.message shouldContain "invalid Base64"
	}

	test("references the stored copy when the source file is gone") {
		val dir = newDir()
		coEvery { trustStore.reference(any(), any(), any()) } returns Unit.right()
		coEvery { trustStore.scopes() } returns setOf(TrustScope.Global).right()
		coEvery { trustStore.list(TrustScope.Global) } returns listOf(anchor("sha256-pin")).right()

		reconciler.reconcile(
			globalConfig(
				TrustedCertificateRef(path = "gone.pem", type = TrustedCertificateType.TSA, fingerprint = "sha256-pin"),
			),
			dir,
		)

		coVerify { trustStore.reference(TrustScope.Global, "sha256-pin", TrustedCertificateType.TSA) }
	}

	test("a source that is gone with no stored copy left aborts startup saying so") {
		val dir = newDir()
		coEvery { trustStore.reference(any(), any(), any()) } returns
			TrustStoreError.noStoredCertificate("sha256-pin", TrustScope.Global).left()

		val failure = shouldThrow<IllegalStateException> {
			reconciler.reconcile(
				globalConfig(TrustedCertificateRef(path = "gone.pem", type = TrustedCertificateType.CA, fingerprint = "sha256-pin")),
				dir,
			)
		}

		failure.message shouldContain "the source gone.pem is gone and no stored copy exists for sha256-pin"
	}

	test("a stored copy the store cannot reference for another reason aborts startup with the store's own message") {
		val dir = newDir()
		val unreadable = TrustStoreError.indexUnreadable("/srv/trusted-certs/contents.cbor")
		coEvery { trustStore.reference(any(), any(), any()) } returns unreadable.left()

		val failure = shouldThrow<IllegalStateException> {
			reconciler.reconcile(
				globalConfig(TrustedCertificateRef(path = "gone.pem", type = TrustedCertificateType.CA, fingerprint = "sha256-pin")),
				dir,
			)
		}

		failure.message shouldContain unreadable.message
		failure.message shouldNotContain "no stored copy"
	}

	test("a real store whose index cannot be read aborts startup with its own message and leaves the directory as it is") {
		val dir = newDir()
		val index = dir.resolve("contents.cbor")
		index.writeBytes(byteArrayOf(0xA1.toByte()))

		val failure = shouldThrow<IllegalStateException> {
			TrustReconciler(FileTrustStore(dir)).reconcile(AppConfig(), dir)
		}

		failure.message shouldContain "enumerate trust scopes"
		failure.message shouldContain index.toAbsolutePath().toString()
		dir.listDirectoryEntries().map { it.name } shouldBe listOf("contents.cbor")
		index.readBytes().toList() shouldBe listOf(0xA1.toByte())
	}

	test("clears a scope the configuration no longer declares") {
		val dir = newDir()
		coEvery { trustStore.scopes() } returns setOf(TrustScope.Global, TrustScope.Profile("old")).right()
		coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()
		coEvery { trustStore.clearProfileScope("old") } returns Unit.right()

		reconciler.reconcile(AppConfig(global = GlobalConfig()), dir)

		coVerify { trustStore.clearProfileScope("old") }
	}

	test("rejects an entry that sets both path and inline") {
		val dir = newDir()
		shouldThrow<IllegalStateException> {
			reconciler.reconcile(
				globalConfig(TrustedCertificateRef(path = "a.pem", inline = "AAAA", type = TrustedCertificateType.CA)),
				dir,
			)
		}
	}
})
