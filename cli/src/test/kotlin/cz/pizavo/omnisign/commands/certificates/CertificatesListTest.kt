package cz.pizavo.omnisign.commands.certificates

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning
import cz.pizavo.omnisign.domain.usecase.ListCertificatesUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module
import kotlin.time.Instant

/**
 * Behavioral tests for the [CertificatesList] CLI command.
 */
class CertificatesListTest : FunSpec({

	val signingRepository: SigningRepository = mockk()

	fun cert(alias: String) = AvailableCertificateInfo(
		alias = alias,
		subjectDN = "CN=$alias",
		issuerDN = "CN=CA",
		validFrom = Instant.parse("2024-01-01T00:00:00Z"),
		validTo = Instant.parse("2027-01-01T00:00:00Z"),
		tokenType = "FILE",
		keyUsages = listOf("digitalSignature"),
	)

	extension(
		KoinExtension(
			module {
				single { ListCertificatesUseCase(signingRepository) }
				single<PasswordCallback> { mockk() }
			},
			mode = KoinLifecycleMode.Test
		)
	)

	test("lists certificates on success") {
		coEvery { signingRepository.listAvailableCertificates() } returns CertificateDiscoveryResult(
			certificates = listOf(cert("my-cert")),
		).right()

		val result = Omnisign().test(listOf("certificates", "list"))

		result.output shouldContain "AVAILABLE CERTIFICATES"
		result.output shouldContain "my-cert"
		result.statusCode shouldBe 0
	}

	test("empty certificate list shows guidance message") {
		coEvery { signingRepository.listAvailableCertificates() } returns CertificateDiscoveryResult(
			certificates = emptyList(),
		).right()

		val result = Omnisign().test(listOf("certificates", "list"))

		result.output shouldContain "No certificates found"
		result.statusCode shouldBe 0
	}

	test("token warnings are shown on stderr") {
		coEvery { signingRepository.listAvailableCertificates() } returns CertificateDiscoveryResult(
			certificates = listOf(cert("ok")),
			tokenWarnings = listOf(
				TokenDiscoveryWarning("t1", "Smart Card", "Access denied")
			),
		).right()

		val result = Omnisign().test(listOf("certificates", "list"))

		result.stderr shouldContain "Smart Card"
		result.statusCode shouldBe 0
	}

	test("error exits with code 1") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			SigningError.TokenAccessError(message = "Discovery failed").left()

		val result = Omnisign().test(listOf("certificates", "list"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Discovery failed"
	}

	test("--json outputs structured JSON on success") {
		coEvery { signingRepository.listAvailableCertificates() } returns CertificateDiscoveryResult(
			certificates = listOf(cert("json-cert")),
		).right()

		val result = Omnisign().test(listOf("--json", "certificates", "list"))

		result.output shouldContain "\"success\""
		result.output shouldContain "json-cert"
		result.statusCode shouldBe 0
	}

	test("--json outputs JSON error with exit code 1") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			SigningError.TokenAccessError(message = "Token error").left()

		val result = Omnisign().test(listOf("--json", "certificates", "list"))

		result.output shouldContain "\"success\""
		result.output shouldContain "Token error"
		result.statusCode shouldBe 1
	}
})

