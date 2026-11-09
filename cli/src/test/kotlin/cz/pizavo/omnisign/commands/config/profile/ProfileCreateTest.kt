package cz.pizavo.omnisign.commands.config.profile

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.commands.config.ConfigSet
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.koin.dsl.module

/**
 * Behavioral tests for the [ProfileCreate] command, guarding the same failure mode
 * [cz.pizavo.omnisign.commands.config.ConfigSetTest] was written for: an option that is declared and
 * acknowledged on stdout while never reaching the persisted [ProfileConfig].
 *
 * Also pins the TSA credential handling, where the interesting rule is conditional — the password
 * only reaches the OS keychain when a username exists to key it by, the config file must never
 * carry the password itself, and no profile is saved to refer to a password the keychain did not
 * keep.
 *
 * Every refusal and failure exits with status 1, so a script that creates a profile and then signs
 * with it stops instead of signing without the settings it asked for.
 */
class ProfileCreateTest : FunSpec({

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

	beforeTest {
		clearMocks(manageProfile, credentialStore, passwordCallback)
		every { credentialStore.isPersistent } returns true
		every { credentialStore.setPassword(any(), any(), any()) } returns Unit.right()
	}

	/**
	 * Run `config profile create` with [args] and return the [ProfileConfig] it would persist.
	 */
	suspend fun createProfile(vararg args: String): ProfileConfig {
		val profile = slot<ProfileConfig>()
		coEvery { manageProfile.upsert(capture(profile)) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "profile", "create", *args))

		result.statusCode shouldBe 0
		return profile.captured
	}

	test("refuses to create a profile that carries no settings") {
		val result = Omnisign().test(listOf("config", "profile", "create", "empty"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No settings specified"
		coVerify(exactly = 0) { manageProfile.upsert(any()) }
	}

	test("carries the algorithm and level overrides onto the persisted profile") {
		val profile = createProfile(
			"qualified",
			"--description", "Qualified signatures",
			"--hash-algorithm", "SHA512",
			"--encryption-algorithm", "ECDSA",
			"--signature-level", "PADES_BASELINE_LTA",
		)

		profile.name shouldBe "qualified"
		profile.description shouldBe "Qualified signatures"
		profile.hashAlgorithm shouldBe HashAlgorithm.SHA512
		profile.encryptionAlgorithm shouldBe EncryptionAlgorithm.ECDSA
		profile.signatureLevel shouldBe SignatureLevel.PADES_BASELINE_LTA
	}

	test("collects the repeatable disable options into the profile's disabled sets") {
		val profile = createProfile(
			"strict",
			"--disable-hash-algorithm", "SHA256",
			"--disable-hash-algorithm", "SHA384",
			"--disable-encryption-algorithm", "DSA",
		)

		profile.disabledHashAlgorithms shouldBe setOf(HashAlgorithm.SHA256, HashAlgorithm.SHA384)
		profile.disabledEncryptionAlgorithms shouldBe setOf(EncryptionAlgorithm.DSA)
	}

	test("leaves the timestamp block unset when no TSA option is given") {
		val profile = createProfile("qualified", "--hash-algorithm", "SHA512")

		profile.timestampServer shouldBe null
	}

	test("builds the timestamp block from the TSA options") {
		val profile = createProfile(
			"qualified",
			"--timestamp-url", "https://tsa.example.com",
			"--timestamp-timeout", "5000",
		)

		profile.timestampServer?.url shouldBe "https://tsa.example.com"
		profile.timestampServer?.timeout shouldBe 5000
	}

	test("defaults the timestamp url and timeout when only one TSA option is given") {
		val profile = createProfile("qualified", "--timestamp-username", "tsa-user")

		profile.timestampServer?.url shouldBe ""
		profile.timestampServer?.timeout shouldBe 30000
		profile.timestampServer?.username shouldBe "tsa-user"
	}

	test("stores the TSA password in the credential store, never in the profile") {
		val profile = createProfile(
			"qualified",
			"--timestamp-url", "https://tsa.example.com",
			"--timestamp-username", "tsa-user",
			"--timestamp-password", "s3cret",
		)

		verify {
			credentialStore.setPassword(ConfigSet.TSA_CREDENTIAL_SERVICE, "tsa-user", "s3cret")
		}
		profile.timestampServer?.credentialKey shouldBe "tsa-user"
	}

	test("keeps the password out of the keychain when there is no username to key it by") {
		val profile = createProfile(
			"qualified",
			"--timestamp-url", "https://tsa.example.com",
			"--timestamp-password", "s3cret",
		)

		verify(exactly = 0) { credentialStore.setPassword(any(), any(), any()) }
		profile.timestampServer?.credentialKey shouldBe null
	}

	test("saves no profile when the keyring does not keep the TSA password") {
		every { credentialStore.setPassword(any(), any(), any()) } returns
			ConfigurationError.passwordStoreFailed("Error code 1312").left()
		coEvery { manageProfile.upsert(any()) } returns Unit.right()

		val result = Omnisign().test(
			listOf(
				"config", "profile", "create", "qualified",
				"--timestamp-username", "tsa-user",
				"--timestamp-password", "s3cret",
			)
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "nothing was saved"
		result.stderr shouldContain "Error code 1312"
		coVerify(exactly = 0) { manageProfile.upsert(any()) }
	}

	test("refuses to store the TSA password, without prompting, when no keyring can keep it") {
		every { credentialStore.isPersistent } returns false
		coEvery { manageProfile.upsert(any()) } returns Unit.right()

		val result = Omnisign().test(
			listOf(
				"config", "profile", "create", "qualified",
				"--timestamp-username", "tsa-user",
				"--timestamp-password", "-",
			)
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "no keyring is available"
		verify(exactly = 0) { passwordCallback.requestPassword(any(), any()) }
		coVerify(exactly = 0) { manageProfile.upsert(any()) }
	}

	test("leaves validation unset so the profile inherits the global settings") {
		val profile = createProfile("qualified", "--hash-algorithm", "SHA512")

		profile.validation shouldBe null
	}

	test("carries the validation policy onto the persisted profile") {
		val profile = createProfile("qualified", "--validation-policy", "CUSTOM_FILE")

		profile.validation?.policyType shouldBe ValidationPolicyType.CUSTOM_FILE
	}

	test("carries the algorithm constraint overrides onto the persisted profile") {
		val profile = createProfile(
			"qualified",
			"--algo-expiration-level", "WARN",
			"--algo-expiration-level-after-update", "FAIL",
			"--algo-expiry-override", "RIPEMD160=2030-01-01",
		)

		val constraints = profile.validation?.algorithmConstraints
		constraints?.expirationLevel shouldBe AlgorithmConstraintLevel.WARN
		constraints?.expirationLevelAfterUpdate shouldBe AlgorithmConstraintLevel.FAIL
		constraints?.expirationDateOverrides shouldBe mapOf("RIPEMD160" to "2030-01-01")
	}

	test("reports a save failure on stderr and exits non-zero") {
		coEvery { manageProfile.upsert(any()) } returns
			ConfigurationError.saveFailed(details = "disk full").left()

		val result = Omnisign().test(listOf("config", "profile", "create", "qualified", "-H", "SHA512"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Failed to save profile"
		result.stderr shouldContain "disk full"
	}

	test("confirms the save on stdout") {
		coEvery { manageProfile.upsert(any()) } returns Unit.right()

		val result = Omnisign().test(listOf("config", "profile", "create", "qualified", "-H", "SHA512"))

		result.stdout shouldContain "qualified"
	}
})
