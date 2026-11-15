package cz.pizavo.omnisign.data.repository

import arrow.core.right
import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.TokenType
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.service.SigningToken
import cz.pizavo.omnisign.domain.service.TokenInfo
import cz.pizavo.omnisign.domain.service.TokenService
import eu.europa.esig.dss.enumerations.SignatureAlgorithm
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.pades.validation.PDFDocumentValidator
import eu.europa.esig.dss.pdf.pdfbox.PdfBoxNativeObjectFactory
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Verifies that [DssSigningRepository.signDocument] signs with the encryption algorithm that the
 * parameters or the configuration choose, or with the one the key implies, and refuses one that the
 * key or its token cannot sign with, by signing a PDF with a fresh key of each kind and reading the
 * algorithm back out of the signature.
 *
 * Each key is self-signed and kept in a PKCS#12 file, which the [TokenService] mock hands out as a
 * token of whichever [TokenType] a test asks for, so that the rules for PKCS#11 tokens and the
 * Windows Certificate Store can be checked without either.
 */
class DssSigningRepositoryEncryptionAlgorithmTest : FunSpec({

	val dir = tempdir()
	val password = "test1234"

	/**
	 * A PKCS#12 file named after [name] holding a key from [generator] and a certificate for it,
	 * self-signed with [signatureAlgorithm].
	 */
	fun keystore(name: String, signatureAlgorithm: String, generator: KeyPairGenerator): File {
		val keyPair = generator.generateKeyPair()
		val subject = X500Name("CN=OmniSign Test $name")
		val now = Clock.System.now()
		val holder = JcaX509v3CertificateBuilder(
			subject,
			BigInteger.ONE,
			Date(now.minus(1.days).toEpochMilliseconds()),
			Date(now.plus(365.days).toEpochMilliseconds()),
			subject,
			keyPair.public,
		).build(JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.private))
		val certificate = JcaX509CertificateConverter().getCertificate(holder)
		val store = KeyStore.getInstance("PKCS12").apply {
			load(null, null)
			setKeyEntry(name, keyPair.private, password.toCharArray(), arrayOf(certificate))
		}
		return File(dir, "$name.p12").also { file -> file.outputStream().use { store.store(it, password.toCharArray()) } }
	}

	val rsa = keystore("rsa", "SHA256withRSA", KeyPairGenerator.getInstance("RSA").apply { initialize(2048) })
	val ec = keystore(
		"ec",
		"SHA256withECDSA",
		KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) },
	)
	val ed25519 = keystore("ed25519", "Ed25519", KeyPairGenerator.getInstance("Ed25519"))
	val ed448 = keystore("ed448", "Ed448", KeyPairGenerator.getInstance("Ed448"))

	val trustStore = FileTrustStore(tempdir().toPath())
	val dssServiceFactory = mockk<DssServiceFactory>(relaxed = true)
	every { dssServiceFactory.buildPdfObjectFactory() } answers { PdfBoxNativeObjectFactory() }
	every { dssServiceFactory.buildSigningCertificateVerifier(any(), any(), any()) } answers {
		CertificateVerifierResult(CommonCertificateVerifier())
	}

	/**
	 * A repository whose only token is [keystore], handed out as a token of [type] that needs no PIN.
	 */
	fun repository(keystore: File, type: TokenType = TokenType.FILE): DssSigningRepository {
		val token = TokenInfo(
			id = "test-${keystore.name}",
			name = keystore.name,
			type = type,
			path = keystore.absolutePath,
			requiresPin = false,
		)
		val tokenService = mockk<TokenService>()
		coEvery { tokenService.discoverTokens() } returns listOf(token).right()
		coEvery { tokenService.probeTokenPresent(any()) } returns true
		coEvery { tokenService.openSigningToken(any(), any()) } answers {
			val connection = Pkcs12SignatureToken(keystore, KeyStore.PasswordProtection(password.toCharArray()))
			object : SigningToken {
				override fun getDssToken(): Any = connection
				override fun close() = connection.close()
			}.right()
		}
		return DssSigningRepository(
			tokenService, mockk(), mockk(relaxed = true), dssServiceFactory,
			AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
			trustStore, DocumentInputErrorDetector(), Pkcs11SessionCache(), SignatureSpaceErrorDetector(),
		)
	}

	/**
	 * Resolve a configuration that signs at PAdES-B with [hash] and [encryption], disables
	 * [disabledHash] and [disabledEncryption], and dates algorithms by [constraints].
	 */
	fun resolved(
		hash: HashAlgorithm = HashAlgorithm.SHA256,
		encryption: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEncryption: Set<EncryptionAlgorithm> = emptySet(),
		constraints: AlgorithmConstraintsConfig = AlgorithmConstraintsConfig(),
	): ResolvedConfig = ResolvedConfig.resolve(
		GlobalConfig(
			defaultHashAlgorithm = hash,
			defaultEncryptionAlgorithm = encryption,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
			disabledHashAlgorithms = disabledHash,
			disabledEncryptionAlgorithms = disabledEncryption,
			validation = ValidationConfig(algorithmConstraints = constraints),
		),
		profile = null,
		operationOverrides = null,
	).shouldBeRight()

	/** A one-page PDF with nothing on it. */
	fun plainPdf(): ByteArray {
		val out = ByteArrayOutputStream()
		PDDocument().use { document ->
			document.addPage(PDPage())
			document.save(out)
		}
		return out.toByteArray()
	}

	/** Sign a fresh PDF with [repository] under [config] without a timestamp, asking for [encryption]. */
	suspend fun sign(
		repository: DssSigningRepository,
		config: ResolvedConfig = resolved(),
		encryption: EncryptionAlgorithm? = null,
	): OperationResult<SigningResult> = repository.signDocument(
		SigningParameters(
			inputBytes = plainPdf(),
			inputName = "input.pdf",
			encryptionAlgorithm = encryption,
			addTimestamp = false,
			resolvedConfig = config,
		)
	)

	/**
	 * The algorithm of the only signature in the document [result] holds, once its value has been
	 * checked against the signing certificate's key.
	 */
	fun signatureAlgorithmOf(result: OperationResult<SigningResult>): SignatureAlgorithm {
		val signature = PDFDocumentValidator(InMemoryDocument(result.shouldBeRight().outputBytes))
			.apply { setCertificateVerifier(CommonCertificateVerifier()) }
			.signatures
			.single()
		signature.signatureCryptographicVerification.isSignatureValid.shouldBeTrue()
		return signature.signatureAlgorithm
	}

	test("an RSA key signs with RSA-PSS when the parameters ask for it, and with PKCS#1 v1.5 when nothing names one") {
		val repository = repository(rsa)

		signatureAlgorithmOf(sign(repository, encryption = EncryptionAlgorithm.RSA_SSA_PSS)) shouldBe
				SignatureAlgorithm.RSA_SSA_PSS_SHA256_MGF1
		signatureAlgorithmOf(sign(repository)) shouldBe SignatureAlgorithm.RSA_SHA256
	}

	test("the encryption algorithm the configuration chooses signs when the parameters name none") {
		val config = resolved(encryption = EncryptionAlgorithm.RSA_SSA_PSS)

		signatureAlgorithmOf(sign(repository(rsa), config)) shouldBe SignatureAlgorithm.RSA_SSA_PSS_SHA256_MGF1
	}

	test("an EC key signs with plain ECDSA when asked and with DER-encoded ECDSA otherwise, with the hash chosen") {
		val repository = repository(ec)
		val config = resolved(hash = HashAlgorithm.SHA384)

		signatureAlgorithmOf(sign(repository, config, EncryptionAlgorithm.PLAIN_ECDSA)) shouldBe
				SignatureAlgorithm.PLAIN_ECDSA_SHA384
		signatureAlgorithmOf(sign(repository, config)) shouldBe SignatureAlgorithm.ECDSA_SHA384
	}

	test("an Ed25519 key signs with SHA-512 and an Ed448 key with SHAKE256, whatever hash is configured") {
		val config = resolved(hash = HashAlgorithm.SHA384)

		signatureAlgorithmOf(sign(repository(ed25519), config)) shouldBe SignatureAlgorithm.ED25519
		signatureAlgorithmOf(sign(repository(ed25519), config, EncryptionAlgorithm.EDDSA)) shouldBe
				SignatureAlgorithm.ED25519
		signatureAlgorithmOf(sign(repository(ed448), config)) shouldBe SignatureAlgorithm.ED448
	}

	test("an encryption algorithm of another family than the key's is refused, naming both") {
		val cases = listOf(
			Triple(rsa, EncryptionAlgorithm.ECDSA, "RSA"),
			Triple(ec, EncryptionAlgorithm.RSA_SSA_PSS, "ECDSA"),
			Triple(ed25519, EncryptionAlgorithm.PLAIN_ECDSA, "EDDSA"),
		)

		cases.forEach { (keystore, requested, keyAlgorithm) ->
			val error = sign(repository(keystore), encryption = requested).shouldBeLeft()

			error.shouldBeInstanceOf<SigningError.InvalidParameters>()
			error.text shouldBe LocalizableText.of(MessageKey.SIGNING_ENCRYPTION_KEY_MISMATCH, requested.name, keyAlgorithm)
		}
	}

	test("the algorithm the key implies is refused when the configuration disables it, though RSA-PSS on the key is not") {
		val config = resolved(disabledEncryption = setOf(EncryptionAlgorithm.RSA))

		val error = sign(repository(rsa), config).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		error.message shouldContain "Encryption algorithm RSA is disabled"
		signatureAlgorithmOf(sign(repository(rsa), config, EncryptionAlgorithm.RSA_SSA_PSS)) shouldBe
				SignatureAlgorithm.RSA_SSA_PSS_SHA256_MGF1
	}

	test("an Ed25519 key is refused when the configuration disables or dates out SHA-512, though an Ed448 key is not") {
		val disabled = resolved(disabledHash = setOf(HashAlgorithm.SHA512))
		val datedOut = resolved(
			constraints = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("SHA512" to "2020-01-01")),
		)

		sign(repository(ed25519), disabled).shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
			.message shouldContain "Hash algorithm SHA512 is disabled"
		sign(repository(ed25519), datedOut).shouldBeLeft().shouldBeInstanceOf<SigningError.ExpiredAlgorithm>()
		signatureAlgorithmOf(sign(repository(ed448), disabled)) shouldBe SignatureAlgorithm.ED448
		signatureAlgorithmOf(sign(repository(ed448), datedOut)) shouldBe SignatureAlgorithm.ED448
	}

	test("an Ed25519 key whose SHA-512 is dated out under a WARN level signs, with a warning") {
		val config = resolved(
			constraints = AlgorithmConstraintsConfig(
				expirationLevel = AlgorithmConstraintLevel.WARN,
				expirationDateOverrides = mapOf("SHA512" to "2020-01-01"),
			),
		)

		val result = sign(repository(ed25519), config)

		signatureAlgorithmOf(result) shouldBe SignatureAlgorithm.ED25519
		result.shouldBeRight().rawWarnings shouldExist { "SHA512 expired" in it }
	}

	test("a hash the configuration dates out is refused under a FAIL level before any key is looked up") {
		val strictTokens = DssSigningRepository(
			mockk(), mockk(), mockk(relaxed = true), dssServiceFactory,
			AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
			trustStore, DocumentInputErrorDetector(), Pkcs11SessionCache(), SignatureSpaceErrorDetector(),
		)

		sign(strictTokens, resolved(hash = HashAlgorithm.RIPEMD160)).shouldBeLeft()
			.shouldBeInstanceOf<SigningError.ExpiredAlgorithm>()
			.message shouldContain "RIPEMD160"
	}

	test("a hash the configuration dates out under a WARN level signs, with a warning") {
		val config = resolved(
			hash = HashAlgorithm.RIPEMD160,
			constraints = AlgorithmConstraintsConfig(expirationLevel = AlgorithmConstraintLevel.WARN),
		)

		val result = sign(repository(rsa), config)

		signatureAlgorithmOf(result) shouldBe SignatureAlgorithm.RSA_RIPEMD160
		result.shouldBeRight().rawWarnings shouldExist { "RIPEMD160 expired" in it }
	}

	test("a hash the key's own algorithm cannot pair with is refused once the key is known") {
		val config = resolved(
			hash = HashAlgorithm.WHIRLPOOL,
			constraints = AlgorithmConstraintsConfig(expirationDateOverrides = mapOf("WHIRLPOOL" to "2100-01-01")),
		)

		val error = sign(repository(rsa), config).shouldBeLeft()

		error.shouldBeInstanceOf<SigningError.InvalidParameters>()
		error.text shouldBe LocalizableText.of(
			MessageKey.SIGNING_HASH_ENCRYPTION_INCOMPATIBLE,
			"WHIRLPOOL",
			"RSA",
			EncryptionAlgorithm.RSA.compatibleHashAlgorithms.joinToString { it.name },
		)
	}

	test("a key on a PKCS#11 token or in the Windows Certificate Store is refused RSA-PSS and plain ECDSA") {
		val cases = listOf(
			Triple(rsa, TokenType.PKCS11, EncryptionAlgorithm.RSA_SSA_PSS),
			Triple(ec, TokenType.WINDOWS_MY, EncryptionAlgorithm.PLAIN_ECDSA),
		)

		cases.forEach { (keystore, type, requested) ->
			val error = sign(repository(keystore, type), encryption = requested).shouldBeLeft()

			error.shouldBeInstanceOf<SigningError.InvalidParameters>()
			error.text shouldBe LocalizableText.of(MessageKey.SIGNING_ENCRYPTION_NOT_SUPPORTED_BY_TOKEN, requested.name)
			error.details shouldContain type.name
		}
	}

	test("a key on a PKCS#11 token signs with its own algorithm, and one in a macOS keychain with RSA-PSS") {
		signatureAlgorithmOf(sign(repository(rsa, TokenType.PKCS11))) shouldBe SignatureAlgorithm.RSA_SHA256
		signatureAlgorithmOf(sign(repository(rsa, TokenType.MACOS_KEYCHAIN), encryption = EncryptionAlgorithm.RSA_SSA_PSS)) shouldBe
				SignatureAlgorithm.RSA_SSA_PSS_SHA256_MGF1
	}
})
