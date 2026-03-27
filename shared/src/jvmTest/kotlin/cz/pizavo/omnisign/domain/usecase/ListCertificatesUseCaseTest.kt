package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.TokenDiscoveryWarning
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.time.Instant

/**
 * Verifies [ListCertificatesUseCase] signing-capability filtering logic.
 */
class ListCertificatesUseCaseTest : FunSpec({

	val signingRepository: SigningRepository = mockk()
	val useCase = ListCertificatesUseCase(signingRepository)

	fun cert(
		alias: String,
		subject: String = "CN=$alias",
		issuer: String = "CN=CA",
		keyUsages: List<String> = emptyList()
	) = AvailableCertificateInfo(
		alias = alias,
		subjectDN = subject,
		issuerDN = issuer,
		validFrom = Instant.parse("2024-01-01T00:00:00Z"),
		validTo = Instant.parse("2027-01-01T00:00:00Z"),
		tokenType = "FILE",
		keyUsages = keyUsages
	)

	fun discovery(vararg certs: AvailableCertificateInfo, warnings: List<TokenDiscoveryWarning> = emptyList()) =
		CertificateDiscoveryResult(certificates = certs.toList(), tokenWarnings = warnings)

	test("returns certificates with digitalSignature key usage") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			discovery(cert("a", keyUsages = listOf("digitalSignature"))).right()

		useCase().shouldBeRight().certificates.shouldHaveSize(1)
	}

	test("returns certificates with nonRepudiation key usage") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			discovery(cert("a", keyUsages = listOf("nonRepudiation"))).right()

		useCase().shouldBeRight().certificates.shouldHaveSize(1)
	}

	test("filters out certificates with key usages that lack signing capability") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			discovery(cert("a", keyUsages = listOf("keyEncipherment", "dataEncipherment"))).right()

		useCase().shouldBeRight().certificates.shouldBeEmpty()
	}

	test("certificate with empty key usages and different subject and issuer is included") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			discovery(cert("a", subject = "CN=User", issuer = "CN=CA")).right()

		useCase().shouldBeRight().certificates.shouldHaveSize(1)
	}

	test("self-signed certificate with empty key usages is excluded") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			discovery(cert("a", subject = "CN=Self", issuer = "CN=Self")).right()

		useCase().shouldBeRight().certificates.shouldBeEmpty()
	}

	test("mixed list filters correctly") {
		coEvery { signingRepository.listAvailableCertificates() } returns discovery(
			cert("signing", keyUsages = listOf("digitalSignature")),
			cert("encrypting", keyUsages = listOf("keyEncipherment")),
			cert("no-usage-non-self", subject = "CN=User", issuer = "CN=CA"),
			cert("self-signed", subject = "CN=Dev", issuer = "CN=Dev"),
		).right()

		val result = useCase().shouldBeRight()
		result.certificates.shouldHaveSize(2)
		result.certificates.map { it.alias } shouldBe listOf("signing", "no-usage-non-self")
	}

	test("token warnings are preserved after filtering") {
		val warning = TokenDiscoveryWarning(tokenId = "t1", tokenName = "Broken Token", message = "Access denied")
		coEvery { signingRepository.listAvailableCertificates() } returns
			discovery(cert("a", keyUsages = listOf("digitalSignature")), warnings = listOf(warning)).right()

		val result = useCase().shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.tokenWarnings.shouldHaveSize(1)
		result.tokenWarnings.first().tokenId shouldBe "t1"
	}

	test("propagates repository error") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			SigningError.TokenAccessError(message = "Discovery failed").left()

		useCase().shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}

	test("returns empty list when repository returns no certificates") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			discovery().right()

		useCase().shouldBeRight().certificates.shouldBeEmpty()
	}
})
