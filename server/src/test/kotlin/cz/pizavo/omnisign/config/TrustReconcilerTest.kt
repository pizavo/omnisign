package cz.pizavo.omnisign.config

import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.time.Instant

/**
 * Verifies [TrustReconciler] imports declared references, enforces the fingerprint pin, references a
 * stored copy when the source is gone, clears scopes the configuration drops, and rejects malformed
 * entries.
 */
class TrustReconcilerTest : FunSpec({

	val trustStore: TrustStore = mockk()
	val reconciler = TrustReconciler(trustStore)

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

	test("imports a path-based reference into the global scope") {
		val dir = Files.createTempDirectory("reconcile")
		dir.resolve("ca.pem").writeBytes(byteArrayOf(1, 2, 3))
		coEvery { trustStore.add(any(), any(), any(), any()) } returns anchor("sha256-aa").right()
		coEvery { trustStore.scopes() } returns setOf(TrustScope.Global).right()
		coEvery { trustStore.list(TrustScope.Global) } returns listOf(anchor("sha256-aa")).right()

		reconciler.reconcile(globalConfig(TrustedCertificateRef(path = "ca.pem", type = TrustedCertificateType.CA)), dir)

		coVerify { trustStore.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem") }
	}

	test("a fingerprint pin mismatch aborts startup") {
		val dir = Files.createTempDirectory("reconcile")
		dir.resolve("ca.pem").writeBytes(byteArrayOf(1, 2, 3))
		coEvery { trustStore.add(any(), any(), any(), any()) } returns anchor("sha256-real").right()

		shouldThrow<IllegalStateException> {
			reconciler.reconcile(
				globalConfig(
					TrustedCertificateRef(path = "ca.pem", type = TrustedCertificateType.CA, fingerprint = "sha256-pinned"),
				),
				dir,
			)
		}
	}

	test("references the stored copy when the source file is gone") {
		val dir = Files.createTempDirectory("reconcile")
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

	test("clears a scope the configuration no longer declares") {
		val dir = Files.createTempDirectory("reconcile")
		coEvery { trustStore.scopes() } returns setOf(TrustScope.Global, TrustScope.Profile("old")).right()
		coEvery { trustStore.list(TrustScope.Global) } returns emptyList<TrustedCertificate>().right()
		coEvery { trustStore.clearProfileScope("old") } returns Unit.right()

		reconciler.reconcile(AppConfig(global = GlobalConfig()), dir)

		coVerify { trustStore.clearProfileScope("old") }
	}

	test("rejects an entry that sets both path and inline") {
		val dir = Files.createTempDirectory("reconcile")
		shouldThrow<IllegalStateException> {
			reconciler.reconcile(
				globalConfig(TrustedCertificateRef(path = "a.pem", inline = "AAAA", type = TrustedCertificateType.CA)),
				dir,
			)
		}
	}
})
