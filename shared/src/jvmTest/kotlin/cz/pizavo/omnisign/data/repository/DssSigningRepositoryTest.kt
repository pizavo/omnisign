package cz.pizavo.omnisign.data.repository

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
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
	
	val repository = DssSigningRepository(
		tokenService, configRepository, credentialStore, dssServiceFactory,
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
	)
	
	fun defaultConfig() = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B
		)
	)
	
	fun tmpFile(name: String) = File(tmpDir, name).also { it.createNewFile() }
	
	test("signDocument returns InvalidParameters when input file does not exist") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		
		val params = SigningParameters(
			inputFile = "/nonexistent/file.pdf",
			outputFile = tmpFile("out.pdf").absolutePath
		)
		
		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.InvalidParameters>()
	}
	
	test("signDocument returns TokenAccessError when token discovery fails") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns SigningError.TokenAccessError(
			message = "No tokens found"
		).left()
		
		val params = SigningParameters(
			inputFile = tmpFile("input.pdf").absolutePath,
			outputFile = tmpFile("out.pdf").absolutePath,
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
			inputFile = tmpFile("input2.pdf").absolutePath,
			outputFile = tmpFile("out2.pdf").absolutePath,
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
		coEvery { tokenService.loadCertificatesSilent(tokenInfo, "") } returns listOf(certEntry).right()

		val params = SigningParameters(
			inputFile = tmpFile("input3.pdf").absolutePath,
			outputFile = tmpFile("out3.pdf").absolutePath,
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
		coEvery { tokenService.loadCertificatesSilent(tokenInfo, "") } returns listOf(certEntry).right()
		coEvery { tokenService.getSigningToken(certEntry, "") } returns SigningError.TokenAccessError(
			message = "PIN incorrect"
		).left()

		val params = SigningParameters(
			inputFile = tmpFile("input4.pdf").absolutePath,
			outputFile = tmpFile("out4.pdf").absolutePath,
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
			message = "Access denied"
		).left()

		val result = repository.listAvailableCertificates().shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.certificates.first().alias shouldBe "cert-a"
		result.tokenWarnings.shouldHaveSize(1)
		result.tokenWarnings.first().tokenId shouldBe "t2"
		result.tokenWarnings.first().message shouldBe "Access denied"
	}
	
	test("listAvailableCertificates returns TokenAccessError when discovery fails") {
		coEvery { tokenService.discoverTokens() } returns SigningError.TokenAccessError(
			message = "No tokens"
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
			inputFile = tmpFile("input5.pdf").absolutePath,
			outputFile = tmpFile("out5.pdf").absolutePath,
			addTimestamp = true
		)

		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.TimestampError>()
	}
	
	test("signDocument returns InvalidParameters when encryption and hash algorithms are incompatible") {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		
		val params = SigningParameters(
			inputFile = tmpFile("input6.pdf").absolutePath,
			outputFile = tmpFile("out6.pdf").absolutePath,
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
			inputFile = tmpFile("input7.pdf").absolutePath,
			outputFile = tmpFile("out7.pdf").absolutePath,
			hashAlgorithm = HashAlgorithm.RIPEMD160,
			encryptionAlgorithm = EncryptionAlgorithm.DSA,
			addTimestamp = false
		)
		
		repository.signDocument(params)
			.shouldBeLeft()
			.shouldBeInstanceOf<SigningError.InvalidParameters>()
	}

	test("listAvailableCertificates separates locked tokens from warnings") {
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
		coEvery { tokenService.loadCertificatesSilent(freeToken, null) } returns listOf(cert).right()

		val result = repository.listAvailableCertificates().shouldBeRight()
		result.certificates.shouldHaveSize(1)
		result.lockedTokens.shouldHaveSize(1)
		result.lockedTokens.first().tokenId shouldBe "t1"
		result.tokenWarnings.shouldBeEmpty()
	}

	test("resolvePrivateKey does not prompt for PIN when cert is found on a non-PIN token") {
		val qscd = TokenInfo(id = "qscd-1", name = "QSCD Token", type = TokenType.PKCS11, path = "/lib/qscd.so", requiresPin = true)
		val winStore = TokenInfo(id = "windows-my", name = "Windows MY", type = TokenType.WINDOWS_MY, requiresPin = false)
		val winCert = CertificateEntry(
			alias = "win-cert", subjectDN = "CN=WinUser", issuerDN = "CN=CA",
			serialNumber = "42", validFrom = Instant.parse("2024-01-01T00:00:00Z"), validTo = Instant.parse("2026-01-01T00:00:00Z"),
			keyUsages = emptyList(), tokenInfo = winStore,
		)

		val mockX509 = mockk<java.security.cert.X509Certificate> {
			every { subjectX500Principal } returns javax.security.auth.x500.X500Principal("CN=WinUser")
		}
		val mockCertToken = mockk<eu.europa.esig.dss.model.x509.CertificateToken> {
			every { certificate } returns mockX509
		}
		val mockKey = mockk<eu.europa.esig.dss.token.DSSPrivateKeyEntry> {
			every { certificate } returns mockCertToken
			every { certificateChain } returns arrayOf(mockCertToken)
		}
		val mockDssToken = mockk<eu.europa.esig.dss.token.AbstractSignatureTokenConnection>(relaxed = true) {
			every { keys } returns listOf(mockKey)
		}
		val mockSigningToken = mockk<SigningToken> {
			every { getDssToken() } returns mockDssToken
		}

		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(qscd, winStore).right()
		coEvery { tokenService.probeTokenPresent(qscd) } returns true
		coEvery { tokenService.probeTokenPresent(winStore) } returns true
		coEvery { credentialStore.getPassword(any(), "qscd-1") } returns null
		coEvery { tokenService.loadCertificatesSilent(winStore, "") } returns listOf(winCert).right()
		coEvery { tokenService.getSigningToken(winCert, "") } returns mockSigningToken.right()

		val params = SigningParameters(
			inputFile = tmpFile("pin-skip-input.pdf").absolutePath,
			outputFile = tmpFile("pin-skip-out.pdf").absolutePath,
			certificateAlias = "win-cert",
			addTimestamp = false,
		)

		repository.signDocument(params)

		io.mockk.coVerify(exactly = 0) { tokenService.requestPin(qscd) }
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
})
