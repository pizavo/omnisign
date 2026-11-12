package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import eu.europa.esig.dss.model.DSSException
import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException
import javax.security.auth.login.FailedLoginException
import javax.security.auth.login.LoginException
import kotlin.time.Instant

/**
 * Verifies that [DssSigningRepository] does not submit a stored token PIN to a slot that has refused
 * it, neither when listing certificates nor when signing, so that a stale PIN in the keychain cannot
 * use up the token's PIN retries.
 *
 * Each test builds its own repository, because the refusals it remembers last as long as it does.
 */
class DssSigningRepositoryStoredPinTest : FunSpec({

	val card = TokenInfo(
		id = "pkcs11-CARD", name = "Card", type = TokenType.PKCS11,
		path = "/lib/opensc.so", requiresPin = true, pkcs11SlotId = 0L,
	)
	val certificate = CertificateEntry(
		alias = "User-7@pkcs11-CARD", subjectDN = "CN=User", issuerDN = "CN=CA", serialNumber = "7",
		validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2027-01-01T00:00:00Z"),
		keyUsages = listOf("nonRepudiation"), tokenInfo = card, pkcs11SlotId = 0L,
	)
	val trustStore = FileTrustStore(tempdir().toPath())

	lateinit var tokenService: TokenService
	lateinit var credentialStore: CredentialStore
	lateinit var repository: DssSigningRepository

	beforeTest {
		tokenService = mockk()
		credentialStore = mockk(relaxed = true)
		val configRepository = mockk<ConfigRepository>()
		repository = DssSigningRepository(
			tokenService, configRepository, credentialStore, mockk(relaxed = true),
			AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
			trustStore, DocumentInputErrorDetector(), Pkcs11SessionCache(), SignatureSpaceErrorDetector(),
		)
		coEvery { configRepository.getCurrentConfig() } returns AppConfig(
			global = GlobalConfig(
				defaultHashAlgorithm = HashAlgorithm.SHA256,
				defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
			)
		)
		coEvery { tokenService.discoverTokens() } returns listOf(card).right()
		coEvery { tokenService.probeTokenPresent(any()) } returns true
		coEvery { tokenService.listCertificatesNoLogin(any()) } returns emptyList<CertificateEntry>().right()
	}

	/**
	 * Build the exception DSS throws when the token refuses the PIN at `C_Login`.
	 */
	fun wrongPin() = DSSException("Bad password for PKCS11", IOException("load failed", FailedLoginException().apply {
		initCause(Exception("CKR_PIN_INCORRECT"))
	}))

	/**
	 * Build what [TokenService.loadCertificatesSilent] returns when the token refuses the PIN.
	 */
	fun refusedListing() =
		SigningError.loadCertificatesFromTokenFailed(card.name, details = "Bad password for PKCS11", cause = wrongPin()).left()

	/**
	 * Build the parameters of a signature with the certificate on [slotId] of the card.
	 */
	fun signingParameters(slotId: Long) = SigningParameters(
		inputBytes = "%PDF-1.7".encodeToByteArray(),
		inputName = "input.pdf",
		certificateAlias = certificate.alias,
		certificateSlotId = slotId,
		addTimestamp = false,
	)

	/**
	 * Build a [SigningToken] whose keys are read through [connection].
	 */
	fun signingToken(connection: AbstractSignatureTokenConnection) =
		mockk<SigningToken> { every { getDssToken() } returns connection }.right()

	test("a stored PIN the token refused while listing is not submitted to it again") {
		every { credentialStore.getPassword(any(), card.id) } returns "0000"
		coEvery { tokenService.loadCertificatesSilent(card, "0000") } returns refusedListing()

		repeat(3) {
			val result = repository.listAvailableCertificates(promptForLocked = false).shouldBeRight()
			result.lockedTokens.map { it.tokenId } shouldBe listOf(card.id)
		}

		coVerify(exactly = 1) { tokenService.loadCertificatesSilent(card, "0000") }
	}

	test("a token that refused its stored PIN is listed by its public certificates") {
		every { credentialStore.getPassword(any(), card.id) } returns "0000"
		coEvery { tokenService.loadCertificatesSilent(card, "0000") } returns refusedListing()
		coEvery { tokenService.listCertificatesNoLogin(card) } returns listOf(certificate).right()

		val result = repository.listAvailableCertificates(promptForLocked = false).shouldBeRight()

		result.certificates.map { it.alias } shouldBe listOf(certificate.alias)
		result.lockedTokens.shouldBeEmpty()
	}

	test("a changed stored PIN is submitted again") {
		var storedPin = "0000"
		every { credentialStore.getPassword(any(), card.id) } answers { storedPin }
		coEvery { tokenService.loadCertificatesSilent(card, "0000") } returns refusedListing()
		coEvery { tokenService.loadCertificatesSilent(card, "1234") } returns listOf(certificate).right()

		repository.listAvailableCertificates(promptForLocked = false)
		storedPin = "1234"
		val result = repository.listAvailableCertificates(promptForLocked = false).shouldBeRight()

		result.certificates.map { it.alias } shouldBe listOf(certificate.alias)
		coVerify(exactly = 1) { tokenService.loadCertificatesSilent(card, "1234") }
	}

	test("a login failure other than a refused PIN does not stop the stored PIN") {
		every { credentialStore.getPassword(any(), card.id) } returns "1234"
		val deviceError = IOException("load failed", LoginException().apply { initCause(Exception("CKR_DEVICE_ERROR")) })
		coEvery { tokenService.loadCertificatesSilent(card, "1234") } returns
			SigningError.loadCertificatesFromTokenFailed(card.name, cause = DSSException("Device error", deviceError)).left()

		repeat(2) { repository.listAvailableCertificates(promptForLocked = false) }

		coVerify(exactly = 2) { tokenService.loadCertificatesSilent(card, "1234") }
	}

	test("concurrent listings submit a stored PIN the token refuses only once") {
		every { credentialStore.getPassword(any(), card.id) } returns "0000"
		coEvery { tokenService.loadCertificatesSilent(card, "0000") } coAnswers {
			delay(100)
			refusedListing()
		}

		coroutineScope {
			List(3) { async { repository.listAvailableCertificates(promptForLocked = false) } }.awaitAll()
		}

		coVerify(exactly = 1) { tokenService.loadCertificatesSilent(card, "0000") }
	}

	test("signing asks for the PIN instead of submitting a stored PIN the slot refused while listing") {
		every { credentialStore.getPassword(any(), card.id) } returns "0000"
		coEvery { tokenService.loadCertificatesSilent(card, "0000") } returns refusedListing()
		coEvery { tokenService.requestPin(card) } returns null

		repository.listAvailableCertificates(promptForLocked = false)
		repository.signDocument(signingParameters(slotId = 0L)).shouldBeLeft()

		coVerify(exactly = 0) { tokenService.openSigningToken(any(), "0000") }
		coVerify(exactly = 1) { tokenService.requestPin(card) }
	}

	test("a stored PIN the token refused while signing is not submitted to it again") {
		val refusingConnection = mockk<AbstractSignatureTokenConnection>(relaxed = true) {
			every { keys } throws wrongPin()
		}
		every { credentialStore.getPassword(any(), card.id) } returns "0000"
		coEvery { tokenService.openSigningToken(card, "0000") } returns signingToken(refusingConnection)
		coEvery { tokenService.requestPin(card) } returns null

		repeat(2) { repository.signDocument(signingParameters(slotId = 0L)).shouldBeLeft() }

		coVerify(exactly = 1) { tokenService.openSigningToken(card, "0000") }
		verify(exactly = 1) { refusingConnection.close() }
		coVerify(exactly = 2) { tokenService.requestPin(card) }
	}

	test("a slot that refused the stored PIN does not keep it from another slot of the card") {
		val slotB = card.copy(pkcs11SlotId = 12L)
		val slotBConnection = mockk<AbstractSignatureTokenConnection>(relaxed = true) {
			every { keys } returns emptyList()
		}
		every { credentialStore.getPassword(any(), card.id) } returns "0000"
		coEvery { tokenService.loadCertificatesSilent(card, "0000") } returns refusedListing()
		coEvery { tokenService.openSigningToken(slotB, "0000") } returns signingToken(slotBConnection)
		coEvery { tokenService.requestPin(card) } returns null

		repository.listAvailableCertificates(promptForLocked = false)
		repository.signDocument(signingParameters(slotId = 12L))

		coVerify(exactly = 1) { tokenService.openSigningToken(slotB, "0000") }
	}
})
