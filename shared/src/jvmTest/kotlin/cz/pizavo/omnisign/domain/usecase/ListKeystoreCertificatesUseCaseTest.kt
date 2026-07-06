package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.value.sensitive
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.SigningRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.time.Instant

/**
 * Verifies [ListKeystoreCertificatesUseCase] delegates to the repository unchanged — no
 * signing-capability filter is applied, and the password (including `null`) is passed through.
 */
class ListKeystoreCertificatesUseCaseTest : FunSpec({

	val signingRepository: SigningRepository = mockk()
	val useCase = ListKeystoreCertificatesUseCase(signingRepository)

	fun cert(alias: String, subject: String = "CN=$alias", issuer: String = "CN=CA") =
		AvailableCertificateInfo(
			alias = alias,
			subjectDN = subject,
			issuerDN = issuer,
			validFrom = Instant.parse("2024-01-01T00:00:00Z"),
			validTo = Instant.parse("2027-01-01T00:00:00Z"),
			tokenType = "FILE",
		)

	test("returns the keystore certificates from the repository") {
		coEvery { signingRepository.listCertificatesFromKeystore("/k.p12", any()) } returns
			listOf(cert("a"), cert("b")).right()

		val result = useCase("/k.p12", "pw".sensitive()).shouldBeRight()
		result.shouldHaveSize(2)
		result.map { it.alias } shouldBe listOf("a", "b")
	}

	test("does not filter out a self-signed keystore certificate with no key usage") {
		coEvery { signingRepository.listCertificatesFromKeystore(any(), any()) } returns
			listOf(cert("self", subject = "CN=Self", issuer = "CN=Self")).right()

		useCase("/k.p12", "pw".sensitive()).shouldBeRight().shouldHaveSize(1)
	}

	test("passes a null password through to the repository") {
		coEvery { signingRepository.listCertificatesFromKeystore("/k.p12", null) } returns
			listOf(cert("a")).right()

		useCase("/k.p12", null).shouldBeRight().shouldHaveSize(1)
	}

	test("propagates repository error") {
		coEvery { signingRepository.listCertificatesFromKeystore(any(), any()) } returns
			SigningError.fileNotFound("/missing.p12").left()

		useCase("/missing.p12", null).shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}
})
