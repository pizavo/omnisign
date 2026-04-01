package cz.pizavo.omnisign.domain.model.config

import arrow.core.getOrElse
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies disabled-algorithm enforcement across global, profile, and operation layers
 * in [ResolvedConfig.resolve].
 */
class DisabledAlgorithmsTest : FunSpec({
	
	fun global(
		defaultHash: HashAlgorithm = HashAlgorithm.SHA256,
		defaultEnc: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEnc: Set<EncryptionAlgorithm> = emptySet()
	) = GlobalConfig(
		defaultHashAlgorithm = defaultHash,
		defaultEncryptionAlgorithm = defaultEnc,
		defaultSignatureLevel = SignatureLevel.PADES_BASELINE_B,
		disabledHashAlgorithms = disabledHash,
		disabledEncryptionAlgorithms = disabledEnc
	)
	
	fun profile(
		hash: HashAlgorithm? = null,
		enc: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEnc: Set<EncryptionAlgorithm> = emptySet()
	) = ProfileConfig(
		name = "test",
		hashAlgorithm = hash,
		encryptionAlgorithm = enc,
		disabledHashAlgorithms = disabledHash,
		disabledEncryptionAlgorithms = disabledEnc
	)
	
	fun operation(
		hash: HashAlgorithm? = null,
		enc: EncryptionAlgorithm? = null,
		disabledHash: Set<HashAlgorithm> = emptySet(),
		disabledEnc: Set<EncryptionAlgorithm> = emptySet()
	) = OperationConfig(
		hashAlgorithm = hash,
		encryptionAlgorithm = enc,
		disabledHashAlgorithms = disabledHash,
		disabledEncryptionAlgorithms = disabledEnc
	)
	
	fun resolveOk(
		global: GlobalConfig,
		profile: ProfileConfig? = null,
		op: OperationConfig? = null
	) = ResolvedConfig.resolve(global, profile, op)
		.getOrElse { throw AssertionError("Expected success but got error: ${it.message}") }
	
	fun resolveErr(
		global: GlobalConfig,
		profile: ProfileConfig? = null,
		op: OperationConfig? = null
	) = ResolvedConfig.resolve(global, profile, op).let { result ->
		result.fold(
			ifLeft = { it },
			ifRight = { throw AssertionError("Expected error but got resolved config") }
		)
	}
	
	test("resolve with empty disabled sets succeeds and reports empty sets") {
		val resolved = resolveOk(global())
		resolved.disabledHashAlgorithms.shouldBeEmpty()
		resolved.disabledEncryptionAlgorithms.shouldBeEmpty()
	}
	
	test("globally disabled hash algorithm is reflected in resolved set") {
		resolveOk(global(disabledHash = setOf(HashAlgorithm.RIPEMD160)))
			.disabledHashAlgorithms.shouldContain(HashAlgorithm.RIPEMD160)
	}
	
	test("globally disabled encryption algorithm is reflected in resolved set") {
		resolveOk(global(disabledEnc = setOf(EncryptionAlgorithm.DSA)))
			.disabledEncryptionAlgorithms.shouldContain(EncryptionAlgorithm.DSA)
	}
	
	test("error when global default hash is in global disabled set") {
		val err = resolveErr(
			global(defaultHash = HashAlgorithm.SHA256, disabledHash = setOf(HashAlgorithm.SHA256))
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "SHA256"
		err.message shouldContain "selected in global config"
		err.message shouldContain "disabled in global config"
	}
	
	test("error when profile hash override is in global disabled set") {
		val err = resolveErr(
			global = global(disabledHash = setOf(HashAlgorithm.SHA384)),
			profile = profile(hash = HashAlgorithm.SHA384)
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "SHA384"
		err.message shouldContain "selected in profile config"
		err.message shouldContain "disabled in global config"
	}
	
	test("error when operation hash override is in global disabled set") {
		val err = resolveErr(
			global = global(disabledHash = setOf(HashAlgorithm.WHIRLPOOL)),
			op = operation(hash = HashAlgorithm.WHIRLPOOL)
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "WHIRLPOOL"
		err.message shouldContain "selected in operation overrides"
		err.message shouldContain "disabled in global config"
	}
	
	test("error when operation hash override is in profile disabled set") {
		val err = resolveErr(
			global = global(),
			profile = profile(disabledHash = setOf(HashAlgorithm.SHA512)),
			op = operation(hash = HashAlgorithm.SHA512)
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "SHA512"
		err.message shouldContain "selected in operation overrides"
		err.message shouldContain "disabled in profile config"
	}
	
	test("error when global default encryption is in global disabled set") {
		val err = resolveErr(
			global(defaultEnc = EncryptionAlgorithm.RSA, disabledEnc = setOf(EncryptionAlgorithm.RSA))
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "RSA"
		err.message shouldContain "selected in global config"
		err.message shouldContain "disabled in global config"
	}
	
	test("error when profile encryption override is in global disabled set") {
		val err = resolveErr(
			global = global(disabledEnc = setOf(EncryptionAlgorithm.DSA)),
			profile = profile(enc = EncryptionAlgorithm.DSA)
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "DSA"
		err.message shouldContain "selected in profile config"
		err.message shouldContain "disabled in global config"
	}
	
	test("error when operation encryption override is in profile disabled set") {
		val err = resolveErr(
			global = global(),
			profile = profile(disabledEnc = setOf(EncryptionAlgorithm.ECDSA)),
			op = operation(enc = EncryptionAlgorithm.ECDSA)
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "ECDSA"
		err.message shouldContain "selected in operation overrides"
		err.message shouldContain "disabled in profile config"
	}
	
	test("error message lists multiple disabling layers when algorithm disabled in both global and profile") {
		val err = resolveErr(
			global = global(disabledHash = setOf(HashAlgorithm.SHA384)),
			profile = profile(hash = HashAlgorithm.SHA384, disabledHash = setOf(HashAlgorithm.SHA384))
		)
		err.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		err.message shouldContain "selected in profile config"
		err.message shouldContain "global config"
		err.message shouldContain "profile config"
	}
	
	test("disabled sets from all layers are union in resolved config") {
		resolveOk(
			global = global(disabledHash = setOf(HashAlgorithm.RIPEMD160)),
			profile = profile(disabledHash = setOf(HashAlgorithm.WHIRLPOOL)),
			op = operation(disabledHash = setOf(HashAlgorithm.SHA3_512))
		).disabledHashAlgorithms shouldBe
			setOf(HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL, HashAlgorithm.SHA3_512)
	}
	
	test("resolved hash that is not in any disabled set succeeds") {
		resolveOk(
			global = global(
				defaultHash = HashAlgorithm.SHA256,
				disabledHash = setOf(HashAlgorithm.RIPEMD160, HashAlgorithm.WHIRLPOOL)
			)
		).hashAlgorithm shouldBe HashAlgorithm.SHA256
	}
	
	test("operation cannot re-enable globally disabled algorithm by not listing it") {
		resolveErr(
			global = global(disabledHash = setOf(HashAlgorithm.SHA384)),
			op = operation(hash = HashAlgorithm.SHA384)
		).shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
	}
	
	test("null encryption algorithm is never rejected even if set is non-empty") {
		resolveOk(
			global = global(
				defaultEnc = null,
				disabledEnc = setOf(EncryptionAlgorithm.RSA, EncryptionAlgorithm.DSA)
			)
		).encryptionAlgorithm.shouldBeNull()
	}
	
	test("profile can additionally disable algorithms not disabled globally") {
		resolveOk(
			global = global(),
			profile = profile(disabledHash = setOf(HashAlgorithm.SHA3_256))
		).disabledHashAlgorithms.shouldContain(HashAlgorithm.SHA3_256)
	}
})

