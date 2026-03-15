package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.SigningRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk

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
		validFrom = "2024-01-01",
		validTo = "2027-01-01",
		tokenType = "FILE",
		keyUsages = keyUsages
	)

	test("returns certificates with digitalSignature key usage") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			listOf(cert("a", keyUsages = listOf("digitalSignature"))).right()

		useCase().shouldBeRight().shouldHaveSize(1)
	}

	test("returns certificates with nonRepudiation key usage") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			listOf(cert("a", keyUsages = listOf("nonRepudiation"))).right()

		useCase().shouldBeRight().shouldHaveSize(1)
	}

	test("filters out certificates with key usages that lack signing capability") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			listOf(cert("a", keyUsages = listOf("keyEncipherment", "dataEncipherment"))).right()

		useCase().shouldBeRight().shouldBeEmpty()
	}

	test("certificate with empty key usages and different subject and issuer is included") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			listOf(cert("a", subject = "CN=User", issuer = "CN=CA")).right()

		useCase().shouldBeRight().shouldHaveSize(1)
	}

	test("self-signed certificate with empty key usages is excluded") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			listOf(cert("a", subject = "CN=Self", issuer = "CN=Self")).right()

		useCase().shouldBeRight().shouldBeEmpty()
	}

	test("mixed list filters correctly") {
		coEvery { signingRepository.listAvailableCertificates() } returns listOf(
			cert("signing", keyUsages = listOf("digitalSignature")),
			cert("encrypting", keyUsages = listOf("keyEncipherment")),
			cert("no-usage-non-self", subject = "CN=User", issuer = "CN=CA"),
			cert("self-signed", subject = "CN=Dev", issuer = "CN=Dev"),
		).right()

		val result = useCase().shouldBeRight()
		result.shouldHaveSize(2)
		result.map { it.alias } shouldBe listOf("signing", "no-usage-non-self")
	}

	test("propagates repository error") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			SigningError.TokenAccessError(message = "Discovery failed").left()

		useCase().shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}

	test("returns empty list when repository returns no certificates") {
		coEvery { signingRepository.listAvailableCertificates() } returns
			emptyList<AvailableCertificateInfo>().right()

		useCase().shouldBeRight().shouldBeEmpty()
	}
})

