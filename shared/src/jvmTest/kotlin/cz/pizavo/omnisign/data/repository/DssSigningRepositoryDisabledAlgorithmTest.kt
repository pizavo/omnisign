package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.service.Pkcs11SessionCache
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import cz.pizavo.omnisign.domain.service.TokenService
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.confirmVerified
import io.mockk.mockk

/**
 * Verifies that [DssSigningRepository.signDocument] refuses a hash or encryption algorithm that the
 * resolved configuration disables, whichever caller asks for it, before it looks for a signing key.
 *
 * The configurations below allow their own default algorithms, so [ResolvedConfig.resolve] accepts
 * them; only the algorithm the parameters ask for is disabled.
 */
class DssSigningRepositoryDisabledAlgorithmTest : FunSpec({

	val tokenService = mockk<TokenService>()
	val repository = DssSigningRepository(
		tokenService, mockk(), mockk(relaxed = true), mockk(relaxed = true),
		AlgorithmExpirationChecker(), DssWarningSanitizer(), TspErrorDetector(),
		FileTrustStore(tempdir().toPath()), DocumentInputErrorDetector(), Pkcs11SessionCache(),
		SignatureSpaceErrorDetector(),
	)

	/**
	 * Resolve a configuration that signs with SHA-256 at PAdES-B but disables [hash] and [encryption].
	 */
	fun configDisabling(
		hash: Set<HashAlgorithm> = emptySet(),
		encryption: Set<EncryptionAlgorithm> = emptySet(),
	): ResolvedConfig = ResolvedConfig.resolve(
		GlobalConfig(
			defaultHashAlgorithm = HashAlgorithm.SHA256,
			defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
			disabledHashAlgorithms = hash,
			disabledEncryptionAlgorithms = encryption,
		),
		profile = null,
		operationOverrides = null,
	).shouldBeRight()

	/**
	 * Build the parameters of a signature without a timestamp under [config] that asks for [hash]
	 * and [encryption] (the configuration's own choice when `null`).
	 */
	fun parameters(
		config: ResolvedConfig,
		hash: HashAlgorithm? = null,
		encryption: EncryptionAlgorithm? = null,
	) = SigningParameters(
		inputBytes = "%PDF-1.7".encodeToByteArray(),
		inputName = "input.pdf",
		hashAlgorithm = hash,
		encryptionAlgorithm = encryption,
		addTimestamp = false,
		resolvedConfig = config,
	)

	test("a hash algorithm the configuration disables is refused before any key is looked up") {
		val config = configDisabling(hash = setOf(HashAlgorithm.SHA512))

		val error = repository.signDocument(parameters(config, hash = HashAlgorithm.SHA512)).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		error.message shouldContain "SHA512"
		confirmVerified(tokenService)
	}

	test("an encryption algorithm the configuration disables is refused before any key is looked up") {
		val config = configDisabling(encryption = setOf(EncryptionAlgorithm.ECDSA))

		val error = repository.signDocument(parameters(config, encryption = EncryptionAlgorithm.ECDSA)).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		error.message shouldContain "ECDSA"
		confirmVerified(tokenService)
	}
})
