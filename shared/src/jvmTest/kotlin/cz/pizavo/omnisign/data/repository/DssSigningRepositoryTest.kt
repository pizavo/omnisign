package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.*
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.time.Instant

/**
 * Verifies [DssSigningRepository] error handling and certificate aggregation
 * using Arrow [arrow.core.Either] matchers.
 */
class DssSigningRepositoryTest : FunSpec({
	
	val tmpDir = tempdir()
	
	val tokenService: TokenService = mockk()
	val configRepository: ConfigRepository = mockk()
	val credentialStore: CredentialStore = mockk()
	val dssServiceFactory: DssServiceFactory = mockk(relaxed = true)
	val sessionCache = Pkcs11SessionCache()

	val repository = DssSigningRepository(
		tokenService, configRepository, credentialStore, dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), sessionCache,
	)

	beforeTest { sessionCache.invalidateAll() }
	
	fun defaultConfig() = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B
		)
	)
	
	fun tmpFile(name: String) = File(tmpDir, name).also { it.writeBytes("%PDF-1.7".encodeToByteArray()) }

	fun dssKey(subjectDN: String, issuerDN: String, serial: String): eu.europa.esig.dss.token.DSSPrivateKeyEntry {
		val mockX509 = mockk<java.security.cert.X509Certificate> {
			every { subjectX500Principal } returns javax.security.auth.x500.X500Principal(subjectDN)
			every { issuerX500Principal } returns javax.security.auth.x500.X500Principal(issuerDN)
			every { serialNumber } returns java.math.BigInteger(serial)
		}
		val mockCertToken = mockk<eu.europa.esig.dss.model.x509.CertificateToken> {
			every { certificate } returns mockX509
		}
		return mockk<eu.europa.esig.dss.token.DSSPrivateKeyEntry> {
			every { certificate } returns mockCertToken
			every { certificateChain } returns arrayOf(mockCertToken)
		}
	}

	fun signingTokenFor(vararg entries: CertificateEntry): SigningToken {
		val keyEntries = entries.map { dssKey(it.subjectDN, it.issuerDN, it.serialNumber) }
		val mockDssToken = mockk<eu.europa.esig.dss.token.AbstractSignatureTokenConnection>(relaxed = true) {
			every { keys } returns keyEntries
		}
		return mockk<SigningToken> { every { getDssToken() } returns mockDssToken }
	}

	test("signDocument returns TokenAccessError when token discovery fails") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns SigningError.TokenAccessError(
			text = LocalizableText.Literal("No tokens found")
		).left()
		
		val params = SigningParameters(
			inputBytes = tmpFile("input.pdf").readBytes(),
			inputName = "input.pdf",
			addTimestamp = false
		)
		
		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}
	
	test("signDocument returns TokenAccessError when no tokens are available") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns emptyList<TokenInfo>().right()
		
		val params = SigningParameters(
			inputBytes = tmpFile("input2.pdf").readBytes(),
			inputName = "input2.pdf",
			addTimestamp = false
		)
		
		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}
	
	test("signDocument returns TokenAccessError when requested alias is absent") {
		val tokenInfo = TokenInfo(id = "t1", name = "Test Token", type = TokenType.FILE, requiresPin = false)
		val certEntry = CertificateEntry(
			alias = "my-cert", subjectDN = "CN=Test", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo
		)

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo).right()
		coEvery { tokenService.probeTokenPresent(tokenInfo) } returns true
		coEvery { tokenService.openSigningToken(tokenInfo, "") } returns signingTokenFor(certEntry).right()

		val params = SigningParameters(
			inputBytes = tmpFile("input3.pdf").readBytes(),
			inputName = "input3.pdf",
			certificateAlias = "nonexistent-alias",
			addTimestamp = false
		)

		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}

	test("signDocument returns TokenAccessError when signing token creation fails") {
		val tokenInfo = TokenInfo(id = "t1", name = "Test Token", type = TokenType.FILE, requiresPin = false)
		val certEntry = CertificateEntry(
			alias = "my-cert", subjectDN = "CN=Test", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo
		)

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo).right()
		coEvery { tokenService.probeTokenPresent(tokenInfo) } returns true
		coEvery { tokenService.openSigningToken(tokenInfo, "") } returns SigningError.TokenAccessError(
			text = LocalizableText.Literal("PIN incorrect")
		).left()

		val params = SigningParameters(
			inputBytes = tmpFile("input4.pdf").readBytes(),
			inputName = "input4.pdf",
			addTimestamp = false
		)

		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}
	
	test("listAvailableCertificates aggregates from multiple tokens") {
		val tokenInfo1 = TokenInfo(id = "t1", name = "Token 1", type = TokenType.FILE, requiresPin = false)
		val tokenInfo2 = TokenInfo(id = "t2", name = "Token 2", type = TokenType.WINDOWS_MY, requiresPin = false)
		val cert1 = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo1
		)
		val cert2 = CertificateEntry(
			alias = "cert-b", subjectDN = "CN=B", issuerDN = "CN=CA",
			serialNumber = "2", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo2
		)

		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo1, tokenInfo2).right()
		coEvery { tokenService.probeTokenPresent(tokenInfo1) } returns true
		coEvery { tokenService.probeTokenPresent(tokenInfo2) } returns true
		coEvery { tokenService.loadCertificatesSilent(tokenInfo1, null) } returns listOf(cert1).right()
		coEvery { tokenService.loadCertificatesSilent(tokenInfo2, null) } returns listOf(cert2).right()

		val result = repository.listAvailableCertificates().shouldBeRight()
		result.certificates.shouldHaveSize(2)
		result.certificates.map { it.alias } shouldBe listOf("cert-a", "cert-b")
		result.tokenWarnings.shouldBeEmpty()
	}

	test("listAvailableCertificates silently skips tokens that are not physically present") {
		val tokenInfo1 = TokenInfo(id = "t1", name = "Token 1", type = TokenType.FILE, requiresPin = false)
		val tokenInfo2 = TokenInfo(id = "t2", name = "Token 2", type = TokenType.PKCS11, path = "/lib/fake.so", requiresPin = false)
		val cert1 = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo1
		)

		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo1, tokenInfo2).right()
		coEvery { tokenService.probeTokenPresent(tokenInfo1) } returns true
		coEvery { tokenService.probeTokenPresent(tokenInfo2) } returns false
		coEvery { tokenService.loadCertificatesSilent(tokenInfo1, null) } returns listOf(cert1).right()

		val result = repository.listAvailableCertificates().shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.certificates.first().alias shouldBe "cert-a"
		result.tokenWarnings.shouldBeEmpty()
	}

	test("listAvailableCertificates returns warning for tokens that fail to load") {
		val tokenInfo1 = TokenInfo(id = "t1", name = "Token 1", type = TokenType.FILE, requiresPin = false)
		val tokenInfo2 = TokenInfo(id = "t2", name = "Token 2", type = TokenType.FILE, requiresPin = false)
		val cert1 = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo1
		)

		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo1, tokenInfo2).right()
		coEvery { tokenService.probeTokenPresent(tokenInfo1) } returns true
		coEvery { tokenService.probeTokenPresent(tokenInfo2) } returns true
		coEvery { tokenService.loadCertificatesSilent(tokenInfo1, null) } returns listOf(cert1).right()
		coEvery { tokenService.loadCertificatesSilent(tokenInfo2, null) } returns SigningError.TokenAccessError(
			text = LocalizableText.Literal("Access denied")
		).left()

		val result = repository.listAvailableCertificates().shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.certificates.first().alias shouldBe "cert-a"
		result.tokenWarnings.shouldHaveSize(1)
		result.tokenWarnings.first().tokenId shouldBe "t2"
		result.tokenWarnings.first().message.english() shouldBe "Access denied"
	}
	
	test("listAvailableCertificates returns TokenAccessError when discovery fails") {
		coEvery { tokenService.discoverTokens() } returns SigningError.TokenAccessError(
			text = LocalizableText.Literal("No tokens")
		).left()
		
		repository.listAvailableCertificates()
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TokenAccessError>()
	}
	
	test("signDocument returns TimestampError when addTimestamp is true but no TSA is configured") {
		val tokenInfo = TokenInfo(id = "t1", name = "Test Token", type = TokenType.FILE, requiresPin = false)
		val certEntry = CertificateEntry(
			alias = "my-cert", subjectDN = "CN=Test", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo
		)

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo).right()
		coEvery { tokenService.probeTokenPresent(tokenInfo) } returns true
		coEvery { tokenService.loadCertificatesSilent(tokenInfo, "") } returns listOf(certEntry).right()

		val params = SigningParameters(
			inputBytes = tmpFile("input5.pdf").readBytes(),
			inputName = "input5.pdf",
			addTimestamp = true
		)

		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TimestampError>()
	}
	
	test("signDocument returns InvalidParameters when encryption and hash algorithms are incompatible") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		
		val params = SigningParameters(
			inputBytes = tmpFile("input6.pdf").readBytes(),
			inputName = "input6.pdf",
			hashAlgorithm = HashAlgorithm.WHIRLPOOL,
			encryptionAlgorithm = EncryptionAlgorithm.RSA,
			addTimestamp = false
		)
		
		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.InvalidParameters>()
	}
	
	test("signDocument returns InvalidParameters when DSA is used with RIPEMD160") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		
		val params = SigningParameters(
			inputBytes = tmpFile("input7.pdf").readBytes(),
			inputName = "input7.pdf",
			hashAlgorithm = HashAlgorithm.RIPEMD160,
			encryptionAlgorithm = EncryptionAlgorithm.DSA,
			addTimestamp = false
		)
		
		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.InvalidParameters>()
	}

	test("listAvailableCertificates with promptForLocked=false separates locked tokens from warnings") {
		val pinToken = TokenInfo(id = "t1", name = "PIN Token", type = TokenType.PKCS11, path = "/lib/fake.so", requiresPin = true)
		val freeToken = TokenInfo(id = "t2", name = "Free Token", type = TokenType.WINDOWS_MY, requiresPin = false)
		val cert = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = freeToken
		)

		coEvery { tokenService.discoverTokens() } returns listOf(pinToken, freeToken).right()
		coEvery { tokenService.probeTokenPresent(pinToken) } returns true
		coEvery { tokenService.probeTokenPresent(freeToken) } returns true
		coEvery { credentialStore.getPassword(any(), "t1") } returns null
		coEvery { tokenService.listCertificatesNoLogin(pinToken) } returns emptyList<CertificateEntry>().right()
		coEvery { tokenService.loadCertificatesSilent(freeToken, null) } returns listOf(cert).right()

		val result = repository.listAvailableCertificates(promptForLocked = false).shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.lockedTokens.shouldHaveSize(1)
		result.lockedTokens.first().tokenId shouldBe "t1"
		result.tokenWarnings.shouldBeEmpty()
	}

	test("listAvailableCertificates surfaces a PIN token's certs without a PIN when public") {
		val pinToken = TokenInfo(id = "t1", name = "PIN Token", type = TokenType.PKCS11, path = "/lib/fake.so", requiresPin = true)
		val publicCert = CertificateEntry(
			alias = "Vojtech Piza-15dc279", subjectDN = "CN=Vojtech Piza", issuerDN = "CN=PostSignum Qualified CA 4",
			serialNumber = "22921849", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = listOf("nonRepudiation"), tokenInfo = pinToken,
		)

		coEvery { tokenService.discoverTokens() } returns listOf(pinToken).right()
		coEvery { tokenService.probeTokenPresent(pinToken) } returns true
		coEvery { credentialStore.getPassword(any(), "t1") } returns null
		coEvery { tokenService.listCertificatesNoLogin(pinToken) } returns listOf(publicCert).right()

		val result = repository.listAvailableCertificates(promptForLocked = false).shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.certificates.first().alias shouldBe "Vojtech Piza-15dc279"
		result.lockedTokens.shouldBeEmpty()
		result.tokenWarnings.shouldBeEmpty()
	}

	test("listAvailableCertificates with promptForLocked=true loads certs from prompted token") {
		val pinToken = TokenInfo(id = "t1", name = "PIN Token", type = TokenType.PKCS11, path = "/lib/fake.so", requiresPin = true)
		val cert = CertificateEntry(
			alias = "cert-prompt", subjectDN = "CN=Prompt", issuerDN = "CN=CA",
			serialNumber = "9", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = listOf("digitalSignature"), tokenInfo = pinToken,
		)

		coEvery { tokenService.discoverTokens() } returns listOf(pinToken).right()
		coEvery { tokenService.probeTokenPresent(pinToken) } returns true
		coEvery { credentialStore.getPassword(any(), "t1") } returns null
		coEvery { tokenService.listCertificatesNoLogin(pinToken) } returns emptyList<CertificateEntry>().right()
		coEvery { tokenService.loadCertificates(pinToken, null) } returns listOf(cert).right()

		val result = repository.listAvailableCertificates(promptForLocked = true).shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.certificates.first().alias shouldBe "cert-prompt"
		result.lockedTokens.shouldBeEmpty()
		result.tokenWarnings.shouldBeEmpty()
	}

	test("listAvailableCertificates with promptForLocked=true keeps token locked when prompt is cancelled") {
		val pinToken = TokenInfo(id = "t1", name = "PIN Token", type = TokenType.PKCS11, path = "/lib/fake.so", requiresPin = true)

		coEvery { tokenService.discoverTokens() } returns listOf(pinToken).right()
		coEvery { tokenService.probeTokenPresent(pinToken) } returns true
		coEvery { credentialStore.getPassword(any(), "t1") } returns null
		coEvery { tokenService.listCertificatesNoLogin(pinToken) } returns emptyList<CertificateEntry>().right()
		coEvery { tokenService.loadCertificates(pinToken, null) } returns SigningError.TokenAccessError(
			text = LocalizableText.Literal("PIN entry cancelled for 'PIN Token'")
		).left()

		val result = repository.listAvailableCertificates(promptForLocked = true).shouldBeRight()
		result.certificates.shouldBeEmpty()
		result.lockedTokens.shouldHaveSize(1)
		result.lockedTokens.first().tokenId shouldBe "t1"
	}

	test("resolvePrivateKey does not prompt for PIN when cert is found on a non-PIN token") {
		val qscd = TokenInfo(id = "qscd-1", name = "QSCD Token", type = TokenType.PKCS11, path = "/lib/qscd.so", requiresPin = true)
		val winStore = TokenInfo(id = "windows-my", name = "Windows MY", type = TokenType.WINDOWS_MY, requiresPin = false)
		val winCert = CertificateEntry(
			alias = "WinUser-2a@windows-my", subjectDN = "CN=WinUser", issuerDN = "CN=CA",
			serialNumber = "42", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = winStore,
		)

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(qscd, winStore).right()
		coEvery { tokenService.probeTokenPresent(qscd) } returns true
		coEvery { tokenService.probeTokenPresent(winStore) } returns true
		coEvery { credentialStore.getPassword(any(), "qscd-1") } returns null
		coEvery { tokenService.openSigningToken(winStore, "") } returns signingTokenFor(winCert).right()

		val params = SigningParameters(
			inputBytes = tmpFile("pin-skip-input.pdf").readBytes(),
			inputName = "pin-skip-input.pdf",
			certificateAlias = "WinUser-2a@windows-my",
			addTimestamp = false,
		)

		repository.signDocument(params)

		io.mockk.coVerify(exactly = 0) { tokenService.requestPin(qscd) }
	}

	test("signDocument pins the PKCS#11 token to the slot supplied in SigningParameters") {
		val card = TokenInfo(
			id = "pkcs11-XYZ", name = "Card", type = TokenType.PKCS11,
			path = "/lib/opensc.so", requiresPin = true, pkcs11SlotId = 0L,
		)
		val slotBToken = card.copy(pkcs11SlotId = 12L)
		val cert = CertificateEntry(
			alias = "User-7@pkcs11-XYZ", subjectDN = "CN=User", issuerDN = "CN=CA",
			serialNumber = "7", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = slotBToken, pkcs11SlotId = 12L,
		)

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(card).right()
		coEvery { tokenService.probeTokenPresent(card) } returns true
		coEvery { credentialStore.getPassword(any(), "pkcs11-XYZ") } returns "1234"
		coEvery { tokenService.openSigningToken(slotBToken, "1234") } returns signingTokenFor(cert).right()

		val params = SigningParameters(
			inputBytes = tmpFile("slot-input.pdf").readBytes(),
			inputName = "slot-input.pdf",
			certificateAlias = "User-7@pkcs11-XYZ",
			certificateSlotId = 12L,
			addTimestamp = false,
		)

		repository.signDocument(params)

		io.mockk.coVerify { tokenService.openSigningToken(slotBToken, "1234") }
		io.mockk.coVerify(exactly = 0) { tokenService.openSigningToken(card, "1234") }
	}

	test("signDocument falls back to the all-slots probe to find an alias in a non-pinned slot") {
		val card = TokenInfo(
			id = "pkcs11-XYZ", name = "Card", type = TokenType.PKCS11,
			path = "/lib/opensc.so", requiresPin = true, pkcs11SlotId = 0L,
		)
		val slotBToken = card.copy(pkcs11SlotId = 12L)
		val slotACert = CertificateEntry(
			alias = "Other-1@pkcs11-XYZ", subjectDN = "CN=Other", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = card, pkcs11SlotId = 0L,
		)
		val slotBCert = CertificateEntry(
			alias = "User-7@pkcs11-XYZ", subjectDN = "CN=User", issuerDN = "CN=CA",
			serialNumber = "7", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = slotBToken, pkcs11SlotId = 12L,
		)

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(card).right()
		coEvery { tokenService.probeTokenPresent(card) } returns true
		coEvery { credentialStore.getPassword(any(), "pkcs11-XYZ") } returns "1234"
		coEvery { tokenService.openSigningToken(card, "1234") } returns signingTokenFor(slotACert).right()
		coEvery { tokenService.listCertificatesNoLogin(card) } returns listOf(slotACert, slotBCert).right()
		coEvery { tokenService.openSigningToken(slotBToken, "1234") } returns signingTokenFor(slotBCert).right()

		val params = SigningParameters(
			inputBytes = tmpFile("fallback-input.pdf").readBytes(),
			inputName = "fallback-input.pdf",
			certificateAlias = "User-7@pkcs11-XYZ",
			addTimestamp = false,
		)

		repository.signDocument(params)

		io.mockk.coVerify { tokenService.openSigningToken(card, "1234") }
		io.mockk.coVerify { tokenService.listCertificatesNoLogin(card) }
		io.mockk.coVerify { tokenService.openSigningToken(slotBToken, "1234") }
	}

	test("selectSigningKey resolves the certificate by serial when several share a subject DN") {
		val expiredKey = dssKey(subjectDN = "CN=User", issuerDN = "CN=CA", serial = "100")
		val validKey = dssKey(subjectDN = "CN=User", issuerDN = "CN=CA", serial = "200")
		val selectedValid = CertificateEntry(
			alias = "User-c8@pkcs11-XYZ", subjectDN = "CN=User", issuerDN = "CN=CA",
			serialNumber = "200", validFrom = Instant.parse("2025-01-01T00:00:00Z"),
			validTo = Instant.parse("2027-01-01T00:00:00Z"), keyUsages = emptyList(),
			tokenInfo = TokenInfo(id = "pkcs11-XYZ", name = "Card", type = TokenType.PKCS11, requiresPin = true),
		)

		selectSigningKey(listOf(expiredKey, validKey), selectedValid) shouldBe validKey
	}

	test("selectSigningKey falls back to the first key when no certificate matches") {
		val onlyKey = dssKey(subjectDN = "CN=User", issuerDN = "CN=CA", serial = "100")
		val selectedUnknown = CertificateEntry(
			alias = "User-ff@pkcs11-XYZ", subjectDN = "CN=User", issuerDN = "CN=CA",
			serialNumber = "999", validFrom = Instant.parse("2024-01-01T00:00:00Z"),
			validTo = Instant.parse("2026-01-01T00:00:00Z"), keyUsages = emptyList(),
			tokenInfo = TokenInfo(id = "pkcs11-XYZ", name = "Card", type = TokenType.PKCS11, requiresPin = true),
		)

		selectSigningKey(listOf(onlyKey), selectedUnknown) shouldBe onlyKey
	}

	test("listAvailableCertificates uses stored password for PIN token") {
		val pinToken = TokenInfo(id = "t1", name = "PIN Token", type = TokenType.PKCS11, path = "/lib/fake.so", requiresPin = true)
		val cert = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = pinToken
		)

		coEvery { tokenService.discoverTokens() } returns listOf(pinToken).right()
		coEvery { tokenService.probeTokenPresent(pinToken) } returns true
		coEvery { credentialStore.getPassword(any(), "t1") } returns "1234"
		coEvery { tokenService.loadCertificatesSilent(pinToken, "1234") } returns listOf(cert).right()

		val result = repository.listAvailableCertificates().shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.lockedTokens.shouldBeEmpty()
	}

	test("signDocument returns MalformedDocument when the input is not a PDF") {
		val tokenInfo = TokenInfo(id = "win", name = "Windows MY", type = TokenType.WINDOWS_MY, requiresPin = false)
		val cert = CertificateEntry(
			alias = "my-cert", subjectDN = "CN=Test", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = tokenInfo,
		)

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo).right()
		coEvery { tokenService.probeTokenPresent(tokenInfo) } returns true
		coEvery { tokenService.openSigningToken(tokenInfo, "") } returns signingTokenFor(cert).right()

		val params = SigningParameters(
			inputBytes = "this is plainly not a pdf".encodeToByteArray(),
			inputName = "not-a-pdf.pdf",
			certificateAlias = "Test-1@win",
			addTimestamp = false,
		)

		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.MalformedDocument>()
	}

	test("signDocument reuses an unlocked cached session without re-opening or closing the token") {
		val card = TokenInfo(
			id = "pkcs11-CACHED", name = "Card", type = TokenType.PKCS11,
			path = "/lib/opensc.so", requiresPin = true, pkcs11SlotId = 3L,
		)
		val key = dssKey(subjectDN = "CN=Cached User", issuerDN = "CN=CA", serial = "171")
		val cachedToken = mockk<eu.europa.esig.dss.token.AbstractSignatureTokenConnection>(relaxed = true)
		sessionCache.put(card.id, Pkcs11SessionCache.CachedSession(cachedToken, listOf(key)))

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(card).right()
		coEvery { tokenService.probeTokenPresent(card) } returns true

		val params = SigningParameters(
			inputBytes = tmpFile("cached-input.pdf").readBytes(),
			inputName = "cached-input.pdf",
			certificateAlias = "Cached User-ab@pkcs11-CACHED",
			certificateSlotId = 3L,
			addTimestamp = false,
		)

		repository.signDocument(params)

		io.mockk.coVerify(exactly = 0) { tokenService.openSigningToken(card, any()) }
		io.mockk.coVerify(exactly = 0) { tokenService.requestPin(card) }
		io.mockk.verify(exactly = 0) { cachedToken.close() }
	}
})
