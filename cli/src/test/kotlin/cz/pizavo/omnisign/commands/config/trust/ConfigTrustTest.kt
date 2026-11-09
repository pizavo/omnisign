package cz.pizavo.omnisign.commands.config.trust

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempfile
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.koin.dsl.module
import kotlin.time.Instant

/**
 * Behavioral tests for how the `config trust` subcommands report a failure, and for the fingerprint
 * prefix `remove` has to resolve before it removes anything.
 *
 * A prefix that matches no certificate, or more than one, removes nothing and exits with status 1,
 * as does every failure of the store itself. A store that cannot be read is reported as such, not as
 * a prefix that matched nothing, which would send the user looking for a typo.
 */
class ConfigTrustTest : FunSpec({

	val trustStore: TrustStore = mockk()

	extension(
		KoinExtension(
			module { single { trustStore } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(trustStore) }

	val certificateFile = tempfile(suffix = ".pem")

	/** A trusted CA certificate stored under [fingerprint]. */
	fun trusted(fingerprint: String) = TrustedCertificate(
		fingerprint = fingerprint,
		subjectDN = "CN=OmniSign Test CA",
		notBefore = Instant.parse("2026-01-01T00:00:00Z"),
		notAfter = Instant.parse("2036-01-01T00:00:00Z"),
		type = TrustedCertificateType.CA,
	)

	test("add reports a file that is not a certificate and exits non-zero") {
		coEvery { trustStore.add(TrustScope.Global, any(), TrustedCertificateType.ANY, any()) } returns
			TrustStoreError.parseFailed(details = "not an X.509 certificate").left()

		val result = Omnisign().test(listOf("config", "trust", "add", "--cert", certificateFile.absolutePath))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Could not parse the certificate"
		result.stderr shouldContain "Details: not an X.509 certificate"
	}

	test("list reports a store it cannot read and exits non-zero") {
		coEvery { trustStore.list(TrustScope.Global) } returns
			TrustStoreError.operationFailed(details = "index unreadable").left()

		val result = Omnisign().test(listOf("config", "trust", "list"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Details: index unreadable"
	}

	test("remove removes the one certificate a prefix matches") {
		coEvery { trustStore.list(TrustScope.Global) } returns
			listOf(trusted("sha256-1a2b3c"), trusted("sha256-ffee00")).right()
		coEvery { trustStore.remove(TrustScope.Global, "sha256-1a2b3c") } returns Unit.right()

		val result = Omnisign().test(listOf("config", "trust", "remove", "sha256-1a"))

		result.statusCode shouldBe 0
		result.stdout shouldContain "sha256-1a2b3c removed"
	}

	test("remove refuses a prefix that matches no certificate") {
		coEvery { trustStore.list(TrustScope.Global) } returns listOf(trusted("sha256-1a2b3c")).right()

		val result = Omnisign().test(listOf("config", "trust", "remove", "sha256-ff"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No trusted certificate matching 'sha256-ff'"
		coVerify(exactly = 0) { trustStore.remove(any(), any()) }
	}

	test("remove refuses a prefix that matches more than one certificate") {
		coEvery { trustStore.list(TrustScope.Global) } returns
			listOf(trusted("sha256-1a2b3c"), trusted("sha256-1a9f00")).right()

		val result = Omnisign().test(listOf("config", "trust", "remove", "sha256-1a"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "is ambiguous (2 matches)"
		coVerify(exactly = 0) { trustStore.remove(any(), any()) }
	}

	test("remove reports a store it cannot read, rather than a prefix that matched nothing") {
		coEvery { trustStore.list(TrustScope.Global) } returns
			TrustStoreError.operationFailed(details = "index unreadable").left()

		val result = Omnisign().test(listOf("config", "trust", "remove", "sha256-1a"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Details: index unreadable"
		result.stderr shouldNotContain "No trusted certificate matching"
	}

	test("remove reports a removal the store fails, and exits non-zero") {
		coEvery { trustStore.list(TrustScope.Global) } returns listOf(trusted("sha256-1a2b3c")).right()
		coEvery { trustStore.remove(TrustScope.Global, "sha256-1a2b3c") } returns
			TrustStoreError.storageFailed(details = "disk full").left()

		val result = Omnisign().test(listOf("config", "trust", "remove", "sha256-1a"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Details: disk full"
	}
})
