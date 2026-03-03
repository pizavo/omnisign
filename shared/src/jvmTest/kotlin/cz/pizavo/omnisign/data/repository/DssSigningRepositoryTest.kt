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
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [DssSigningRepository] using MockK to isolate DSS token interactions.
 *
 * Tests cover:
 * - Missing input file returns [SigningError.InvalidParameters].
 * - Token discovery failure propagates as a left.
 * - Certificate alias mismatch returns [SigningError.TokenAccessError].
 * - Successful aggregation of certificates from multiple tokens.
 */
class DssSigningRepositoryTest {
	
	@get:Rule
	val tmpFolder = TemporaryFolder()
	
	private val tokenService: TokenService = mockk()
	private val configRepository: ConfigRepository = mockk()
	private val credentialStore: CredentialStore = mockk()
	
	private val repository = DssSigningRepository(tokenService, configRepository, credentialStore)
	
	private fun defaultConfig() = AppConfig(
		global = GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B
		)
	)
	
	@Test
	fun `signDocument returns InvalidParameters when input file does not exist`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		
		val params = SigningParameters(
			inputFile = "/nonexistent/file.pdf",
			outputFile = tmpFolder.newFile("out.pdf").absolutePath
		)
		
		val result = repository.signDocument(params)
		
		assertIs<arrow.core.Either.Left<SigningError.InvalidParameters>>(result)
	}
	
	@Test
	fun `signDocument returns TokenAccessError when token discovery fails`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns SigningError.TokenAccessError(
			message = "No tokens found"
		).left()
		
		val params = SigningParameters(
			inputFile = tmpFolder.newFile("input.pdf").absolutePath,
			outputFile = tmpFolder.newFile("out.pdf").absolutePath
		)
		
		val result = repository.signDocument(params)
		
		assertIs<arrow.core.Either.Left<SigningError.TokenAccessError>>(result)
	}
	
	@Test
	fun `signDocument returns TokenAccessError when no tokens are available`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns emptyList<TokenInfo>().right()
		
		val params = SigningParameters(
			inputFile = tmpFolder.newFile("input.pdf").absolutePath,
			outputFile = tmpFolder.newFile("out.pdf").absolutePath
		)
		
		val result = repository.signDocument(params)
		
		assertIs<arrow.core.Either.Left<SigningError.TokenAccessError>>(result)
	}
	
	@Test
	fun `signDocument returns TokenAccessError when requested alias is absent`() = runBlocking<Unit> {
		val tokenInfo = TokenInfo(id = "t1", name = "Test Token", type = TokenType.FILE)
		val certEntry = CertificateEntry(
			alias = "my-cert",
			subjectDN = "CN=Test",
			issuerDN = "CN=CA",
			serialNumber = "1",
			validFrom = "2024-01-01",
			validTo = "2026-01-01",
			keyUsages = emptyList(),
			tokenInfo = tokenInfo
		)
		
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo).right()
		coEvery { tokenService.loadCertificates(tokenInfo, null) } returns listOf(certEntry).right()
		
		val params = SigningParameters(
			inputFile = tmpFolder.newFile("input.pdf").absolutePath,
			outputFile = tmpFolder.newFile("out.pdf").absolutePath,
			certificateAlias = "nonexistent-alias"
		)
		
		val result = repository.signDocument(params)
		
		assertIs<arrow.core.Either.Left<SigningError.TokenAccessError>>(result)
	}
	
	@Test
	fun `signDocument returns TokenAccessError when signing token creation fails`() = runBlocking<Unit> {
		val tokenInfo = TokenInfo(id = "t1", name = "Test Token", type = TokenType.FILE)
		val certEntry = CertificateEntry(
			alias = "my-cert",
			subjectDN = "CN=Test",
			issuerDN = "CN=CA",
			serialNumber = "1",
			validFrom = "2024-01-01",
			validTo = "2026-01-01",
			keyUsages = emptyList(),
			tokenInfo = tokenInfo
		)
		
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo).right()
		coEvery { tokenService.loadCertificates(tokenInfo, null) } returns listOf(certEntry).right()
		coEvery { tokenService.getSigningToken(certEntry, "") } returns SigningError.TokenAccessError(
			message = "PIN incorrect"
		).left()
		
		val params = SigningParameters(
			inputFile = tmpFolder.newFile("input.pdf").absolutePath,
			outputFile = tmpFolder.newFile("out.pdf").absolutePath
		)
		
		val result = repository.signDocument(params)
		
		assertIs<arrow.core.Either.Left<SigningError.TokenAccessError>>(result)
	}
	
	@Test
	fun `listAvailableCertificates aggregates from multiple tokens`() = runBlocking<Unit> {
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
		coEvery { tokenService.loadCertificatesSilent(tokenInfo1, null) } returns listOf(cert1).right()
		coEvery { tokenService.loadCertificatesSilent(tokenInfo2, null) } returns listOf(cert2).right()
		
		val result = repository.listAvailableCertificates()
		
		assertTrue(result.isRight())
		val certs = (result as arrow.core.Either.Right).value
		assertEquals(2, certs.size)
		assertTrue(certs.any { it.alias == "cert-a" })
		assertTrue(certs.any { it.alias == "cert-b" })
	}
	
	@Test
	fun `listAvailableCertificates silently skips tokens that fail to load`() = runBlocking<Unit> {
		val tokenInfo1 = TokenInfo(id = "t1", name = "Token 1", type = TokenType.FILE)
		val tokenInfo2 = TokenInfo(id = "t2", name = "Token 2", type = TokenType.FILE)
		val cert1 = CertificateEntry(
			alias = "cert-a", subjectDN = "CN=A", issuerDN = "CN=CA",
			serialNumber = "1", validFrom = "2024-01-01", validTo = "2026-01-01",
			keyUsages = emptyList(), tokenInfo = tokenInfo1
		)
		
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo1, tokenInfo2).right()
		coEvery { tokenService.loadCertificatesSilent(tokenInfo1, null) } returns listOf(cert1).right()
		coEvery { tokenService.loadCertificatesSilent(tokenInfo2, null) } returns SigningError.TokenAccessError(
			message = "Access denied"
		).left()
		
		val result = repository.listAvailableCertificates()
		
		assertTrue(result.isRight())
		val certs = (result as arrow.core.Either.Right).value
		assertEquals(1, certs.size)
		assertEquals("cert-a", certs.first().alias)
	}
	
	@Test
	fun `listAvailableCertificates returns TokenAccessError when discovery fails`() = runBlocking<Unit> {
		coEvery { tokenService.discoverTokens() } returns SigningError.TokenAccessError(
			message = "No tokens"
		).left()
		
		val result = repository.listAvailableCertificates()
		
		assertIs<arrow.core.Either.Left<SigningError.TokenAccessError>>(result)
	}
	
	@Test
	fun `signDocument returns TimestampError when addTimestamp is true but no TSA is configured`() = runBlocking<Unit> {
		val tokenInfo = TokenInfo(id = "t1", name = "Test Token", type = TokenType.FILE)
		val certEntry = CertificateEntry(
			alias = "my-cert",
			subjectDN = "CN=Test",
			issuerDN = "CN=CA",
			serialNumber = "1",
			validFrom = "2024-01-01",
			validTo = "2026-01-01",
			keyUsages = emptyList(),
			tokenInfo = tokenInfo
		)
		
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		coEvery { tokenService.discoverTokens() } returns listOf(tokenInfo).right()
		coEvery { tokenService.loadCertificates(tokenInfo, null) } returns listOf(certEntry).right()
		
		val params = SigningParameters(
			inputFile = tmpFolder.newFile("input.pdf").absolutePath,
			outputFile = tmpFolder.newFile("out.pdf").absolutePath,
			addTimestamp = true
		)
		
		val result = repository.signDocument(params)
		
		assertIs<arrow.core.Either.Left<SigningError.TimestampError>>(result)
	}
	
	@Test
	fun `signDocument returns InvalidParameters when encryption and hash algorithms are incompatible`() =
		runBlocking<Unit> {
			coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
			
			val params = SigningParameters(
				inputFile = tmpFolder.newFile("input.pdf").absolutePath,
				outputFile = tmpFolder.newFile("out.pdf").absolutePath,
				hashAlgorithm = HashAlgorithm.WHIRLPOOL,
				encryptionAlgorithm = EncryptionAlgorithm.RSA,
				addTimestamp = false
			)
			
			val result = repository.signDocument(params)
			
			assertIs<arrow.core.Either.Left<SigningError.InvalidParameters>>(result)
		}
	
	@Test
	fun `signDocument returns InvalidParameters when DSA is used with RIPEMD160`() = runBlocking<Unit> {
		coEvery { configRepository.getCurrentConfig() } returns defaultConfig()
		
		val params = SigningParameters(
			inputFile = tmpFolder.newFile("input.pdf").absolutePath,
			outputFile = tmpFolder.newFile("out.pdf").absolutePath,
			hashAlgorithm = HashAlgorithm.RIPEMD160,
			encryptionAlgorithm = EncryptionAlgorithm.DSA,
			addTimestamp = false
		)
		
		val result = repository.signDocument(params)
		
		assertIs<arrow.core.Either.Left<SigningError.InvalidParameters>>(result)
	}
}
