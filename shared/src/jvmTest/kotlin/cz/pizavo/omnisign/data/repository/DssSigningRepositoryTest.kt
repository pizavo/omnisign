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
import cz.pizavo.omnisign.domain.service.CertificateEntry
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File

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
	
	val repository = DssSigningRepository(tokenService, configRepository, credentialStore, dssServiceFactory)
	
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
			serialNumber = "1", validFrom = "2024-01-01", validTo = "2026-01-01",
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
			serialNumber = "1", validFrom = "2024-01-01", validTo = "2026-01-01",
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
		val tokenInfo1 = TokenInfo(id = "t1", name = "Token 1", type = TokenType.FILE)
		val tokenInfo2 = TokenInfo(id = "t2", name = "Token 2", type = TokenType.WINDOWS_MY)
		val cert1 = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = "2024-01-01", validTo = "2026-01-01",
			keyUsages = emptyList(), tokenInfo = tokenInfo1
		)
		val cert2 = CertificateEntry(
			alias = "cert-b", subjectDN = "CN=B", issuerDN = "CN=CA",
			serialNumber = "2", validFrom = "2024-01-01", validTo = "2026-01-01",
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
		val tokenInfo1 = TokenInfo(id = "t1", name = "Token 1", type = TokenType.FILE)
		val tokenInfo2 = TokenInfo(id = "t2", name = "Token 2", type = TokenType.PKCS11, path = "/lib/fake.so")
		val cert1 = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = "2024-01-01", validTo = "2026-01-01",
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
		val tokenInfo1 = TokenInfo(id = "t1", name = "Token 1", type = TokenType.FILE)
		val tokenInfo2 = TokenInfo(id = "t2", name = "Token 2", type = TokenType.FILE)
		val cert1 = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = "2024-01-01", validTo = "2026-01-01",
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
			serialNumber = "1", validFrom = "2024-01-01", validTo = "2026-01-01",
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
})
