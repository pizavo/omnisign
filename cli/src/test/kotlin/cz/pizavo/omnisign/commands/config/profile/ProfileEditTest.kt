package cz.pizavo.omnisign.commands.config.profile

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.koin.dsl.module

/**
 * Behavioral tests for the [ProfileEdit] command's patch semantics: a supplied option replaces one
 * field, everything else survives untouched, and a `--clear-*` flag is the only way to unset a
 * field.
 *
 * The merge is where this command can go quietly wrong. An option that silently fails to apply
 * leaves the user believing a setting took effect, and an over-eager patch wipes a neighbouring
 * field the user never mentioned — both report the same cheerful "updated" line either way. The
 * clear-versus-set precedence and the add/remove arithmetic on the disabled-algorithm sets are
 * pinned for the same reason.
 */
class ProfileEditTest : FunSpec({

	val manageProfile: ManageProfileUseCase = mockk()
	val credentialStore: CredentialStore = mockk(relaxed = true)
	val passwordCallback: PasswordCallback = mockk(relaxed = true)

	extension(
		KoinExtension(
			module {
				single { manageProfile }
				single { credentialStore }
				single { passwordCallback }
			},
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageProfile, credentialStore, passwordCallback) }

	val existing = ProfileConfig(
		name = "qualified",
		description = "Qualified signatures",
		hashAlgorithm = HashAlgorithm.SHA256,
		encryptionAlgorithm = EncryptionAlgorithm.RSA,
		signatureLevel = SignatureLevel.PADES_BASELINE_T,
		disabledHashAlgorithms = setOf(HashAlgorithm.SHA384),
		disabledEncryptionAlgorithms = setOf(EncryptionAlgorithm.DSA),
		timestampServer = TimestampServerConfig(
			url = "https://tsa.example.com",
			username = "tsa-user",
			credentialKey = "tsa-user",
			timeout = 5000,
		),
		validation = ValidationConfig(
			policyType = ValidationPolicyType.CUSTOM_FILE,
			algorithmConstraints = AlgorithmConstraintsConfig(
				expirationLevel = AlgorithmConstraintLevel.WARN,
				expirationDateOverrides = mapOf("RIPEMD160" to "2030-01-01"),
			),
		),
	)

	/**
	 * Run `config profile edit` with [args] against [base] and return the patched [ProfileConfig].
	 */
	suspend fun editProfile(vararg args: String, base: ProfileConfig = existing): ProfileConfig {
		val updated = slot<ProfileConfig>()
		coEvery { manageProfile.get(base.name) } returns base.right()
		coEvery { manageProfile.upsert(capture(updated)) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "profile", "edit", base.name, *args))

		result.statusCode shouldBe 0
		return updated.captured
	}

	test("refuses an edit that changes nothing") {
		val result = Omnisign().test(listOf("config", "profile", "edit", "qualified"))

		result.stderr shouldContain "No changes specified"
		coVerify(exactly = 0) { manageProfile.get(any()) }
		coVerify(exactly = 0) { manageProfile.upsert(any()) }
	}

	test("names the missing profile without saving anything") {
		coEvery { manageProfile.get("ghost") } returns ConfigurationError.profileNotFound("ghost").left()

		val result = Omnisign().test(listOf("config", "profile", "edit", "ghost", "-H", "SHA512"))

		result.stderr shouldContain "Profile 'ghost' does not exist"
		coVerify(exactly = 0) { manageProfile.upsert(any()) }
	}

	test("surfaces a cause that says something the details do not") {
		coEvery { manageProfile.get("qualified") } returns ConfigurationError.loadFailed(
			details = "config file is not valid JSON",
			cause = RuntimeException("Unexpected token at offset 42"),
		).left()

		val result = Omnisign().test(listOf("config", "profile", "edit", "qualified", "-H", "SHA512"))

		result.stderr shouldContain "Details: config file is not valid JSON"
		result.stderr shouldContain "Cause: Unexpected token at offset 42"
	}

	test("suppresses a cause that merely repeats the details") {
		val message = "config file is not valid JSON"
		coEvery { manageProfile.get("qualified") } returns ConfigurationError.loadFailed(
			details = message,
			cause = RuntimeException(message),
		).left()

		val result = Omnisign().test(listOf("config", "profile", "edit", "qualified", "-H", "SHA512"))

		result.stderr shouldContain "Details: $message"
		result.stderr shouldNotContain "Cause:"
	}

	test("surfaces the details of a load failure that carries any") {
		coEvery { manageProfile.get("qualified") } returns
			ConfigurationError.loadFailed(details = "config file is not valid JSON").left()

		val result = Omnisign().test(listOf("config", "profile", "edit", "qualified", "-H", "SHA512"))

		result.stderr shouldContain "Details: config file is not valid JSON"
		coVerify(exactly = 0) { manageProfile.upsert(any()) }
	}

	test("changes only the field the option names") {
		val updated = editProfile("--hash-algorithm", "SHA512")

		updated.hashAlgorithm shouldBe HashAlgorithm.SHA512
		updated.description shouldBe "Qualified signatures"
		updated.encryptionAlgorithm shouldBe EncryptionAlgorithm.RSA
		updated.signatureLevel shouldBe SignatureLevel.PADES_BASELINE_T
		updated.disabledHashAlgorithms shouldBe setOf(HashAlgorithm.SHA384)
		updated.timestampServer shouldBe existing.timestampServer
		updated.validation shouldBe existing.validation
	}

	test("unsets an optional field only when its clear flag is given") {
		val updated = editProfile(
			"--clear-description",
			"--clear-hash-algorithm",
			"--clear-encryption-algorithm",
			"--clear-signature-level",
		)

		updated.description shouldBe null
		updated.hashAlgorithm shouldBe null
		updated.encryptionAlgorithm shouldBe null
		updated.signatureLevel shouldBe null
	}

	test("lets the clear flag win when the same field is also set") {
		val updated = editProfile("--hash-algorithm", "SHA512", "--clear-hash-algorithm")

		updated.hashAlgorithm shouldBe null
	}

	test("adds to and removes from the disabled hash set in one edit") {
		val updated = editProfile(
			"--disable-hash-algorithm", "SHA256",
			"--enable-hash-algorithm", "SHA384",
		)

		updated.disabledHashAlgorithms shouldBe setOf(HashAlgorithm.SHA256)
	}

	test("lets enable win over disable for the same algorithm") {
		val updated = editProfile(
			"--disable-hash-algorithm", "SHA512",
			"--enable-hash-algorithm", "SHA512",
		)

		updated.disabledHashAlgorithms shouldBe setOf(HashAlgorithm.SHA384)
	}

	test("adds to and removes from the disabled encryption set") {
		val updated = editProfile(
			"--disable-encryption-algorithm", "EDDSA",
			"--enable-encryption-algorithm", "DSA",
		)

		updated.disabledEncryptionAlgorithms shouldBe setOf(EncryptionAlgorithm.EDDSA)
	}

	test("patches one timestamp field and keeps the rest of the block") {
		val updated = editProfile("--timestamp-timeout", "9000")

		updated.timestampServer?.timeout shouldBe 9000
		updated.timestampServer?.url shouldBe "https://tsa.example.com"
		updated.timestampServer?.username shouldBe "tsa-user"
		updated.timestampServer?.credentialKey shouldBe "tsa-user"
	}

	test("removes the whole timestamp block on request") {
		val updated = editProfile("--clear-timestamp")

		updated.timestampServer shouldBe null
	}

	test("keeps the existing validation block when no validation option is given") {
		val updated = editProfile("--hash-algorithm", "SHA512")

		updated.validation shouldBe existing.validation
	}

	test("drops the whole validation block when the policy alone is cleared") {
		val updated = editProfile("--clear-validation-policy")

		updated.validation shouldBe null
	}

	test("merges a new expiry override onto the existing ones") {
		val updated = editProfile("--algo-expiry-override", "SHA1=2028-01-01")

		updated.validation?.algorithmConstraints?.expirationDateOverrides shouldBe mapOf(
			"RIPEMD160" to "2030-01-01",
			"SHA1" to "2028-01-01",
		)
	}

	test("removes every expiry override on request") {
		val updated = editProfile("--clear-algo-expiry-overrides")

		updated.validation?.algorithmConstraints?.expirationDateOverrides shouldBe emptyMap()
	}

	test("resets the algorithm constraints to defaults on request") {
		val updated = editProfile("--clear-algo-constraints")

		val constraints = updated.validation?.algorithmConstraints.shouldNotBeNull()
		constraints.expirationLevel shouldBe null
		constraints.expirationLevelAfterUpdate shouldBe null
		constraints.expirationDateOverrides shouldBe emptyMap()
	}

	test("keeps the existing policy type when only a constraint level changes") {
		val updated = editProfile("--algo-expiration-level", "FAIL")

		updated.validation?.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
		updated.validation?.algorithmConstraints?.expirationLevel shouldBe AlgorithmConstraintLevel.FAIL
	}

	test("reports a save failure on stderr") {
		coEvery { manageProfile.get("qualified") } returns existing.right()
		coEvery { manageProfile.upsert(any()) } returns
			ConfigurationError.saveFailed(details = "disk full").left()

		val result = Omnisign().test(listOf("config", "profile", "edit", "qualified", "-H", "SHA512"))

		result.stderr shouldContain "Failed to save profile"
		result.stderr shouldContain "disk full"
	}
})
