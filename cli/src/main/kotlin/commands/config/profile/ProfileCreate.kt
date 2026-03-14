package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import cz.pizavo.omnisign.commands.config.ConfigSet
import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.*
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for creating or updating a named profile.
 *
 * The TSA password is stored in the OS-native credential store (keychain) and
 * never written to the plain-text config file.
 */
class ProfileCreate : CliktCommand(name = "create"), KoinComponent {
	private val manageProfile: ManageProfileUseCase by inject()
	private val credentialStore: CredentialStore by inject()
	
	private val name by argument(help = "Profile name")
	private val description by option("--description", "-d", help = "Profile description")
	private val hashAlgorithm by option(
		"--hash-algorithm", "-H",
		help = "Hash algorithm override"
	).enum<HashAlgorithm>()
	private val encryptionAlgorithm by option(
		"--encryption-algorithm", "-E",
		help = "Encryption (signing key) algorithm override"
	).enum<EncryptionAlgorithm>()
	private val signatureLevel by option(
		"--signature-level", "-L",
		help = "Signature level override"
	).enum<SignatureLevel>()
	private val timestampUrl by option("--timestamp-url", help = "Timestamp server URL")
	private val timestampUsername by option("--timestamp-username", help = "Timestamp server HTTP Basic username")
	private val timestampPassword by option(
		"--timestamp-password",
		help = "Timestamp server HTTP Basic password (stored in OS keychain, not in config file)"
	)
	private val timestampTimeout by option(
		"--timestamp-timeout",
		help = "Timestamp server request timeout in milliseconds"
	).int()
	private val validationPolicy by option(
		"--validation-policy",
		help = "Validation policy type override"
	).enum<ValidationPolicyType>()
	private val algoExpirationLevel by option(
		"--algo-expiration-level",
		help = "Algorithm expiration severity (${AlgorithmConstraintLevel.entries.joinToString { it.name }})"
	).enum<AlgorithmConstraintLevel>()
	private val algoExpirationLevelAfterUpdate by option(
		"--algo-expiration-level-after-update",
		help = "Algorithm expiration severity after policy update date " +
				"(${AlgorithmConstraintLevel.entries.joinToString { it.name }})"
	).enum<AlgorithmConstraintLevel>()
	private val algoExpiryOverride by option(
		"--algo-expiry-override",
		help = "Per-algorithm expiration date override: ALGORITHM=DATE (e.g. RIPEMD160=2030-01-01). Repeatable."
	).multiple()
	
	private val disableHashAlgorithm by option(
		"--disable-hash-algorithm",
		help = "Disable a hash algorithm for this profile (in addition to globally disabled ones). Repeatable."
	).enum<HashAlgorithm>().multiple()
	
	private val disableEncryptionAlgorithm by option(
		"--disable-encryption-algorithm",
		help = "Disable an encryption algorithm for this profile (in addition to globally disabled ones). Repeatable."
	).enum<EncryptionAlgorithm>().multiple()
	
	/**
	 * Returns true when at least one option that gives the profile a meaningful configuration
	 * was supplied. A profile with no settings is functionally identical to having no active
	 * profile and therefore has no purpose.
	 */
	private val hasAnyOption: Boolean
		get() = description != null ||
				hashAlgorithm != null || encryptionAlgorithm != null || signatureLevel != null ||
				timestampUrl != null || timestampUsername != null ||
				timestampPassword != null || timestampTimeout != null ||
				validationPolicy != null ||
				algoExpirationLevel != null || algoExpirationLevelAfterUpdate != null ||
				algoExpiryOverride.isNotEmpty() ||
				disableHashAlgorithm.isNotEmpty() || disableEncryptionAlgorithm.isNotEmpty()
	
	override fun help(context: Context): String =
		"Create a named configuration profile"
	
	override fun run(): Unit = runBlocking {
		if (!hasAnyOption) {
			echo(
				"❌ No settings specified. Provide at least one option to create a meaningful profile. Use --help to see available options.",
				err = true
			)
			return@runBlocking
		}
		val tsConfig = buildTimestampConfig()
		val profile = ProfileConfig(
			name = name,
			description = description,
			hashAlgorithm = hashAlgorithm,
			encryptionAlgorithm = encryptionAlgorithm,
			signatureLevel = signatureLevel,
			timestampServer = tsConfig,
			disabledHashAlgorithms = disableHashAlgorithm.toSet(),
			disabledEncryptionAlgorithms = disableEncryptionAlgorithm.toSet(),
			validation = buildValidation()
		)
		manageProfile.upsert(profile).fold(
			ifLeft = { error ->
				echo("❌ Failed to save profile: ${error.message}", err = true)
				error.details?.let { echo("Details: $it", err = true) }
			},
			ifRight = {
				echo("✅ Profile '$name' saved.")
			}
		)
	}
	
	/**
	 * Build a [ValidationConfig] when any validation-related option is supplied.
	 * Returns null when no validation options are provided so the profile inherits global settings.
	 */
	private fun buildValidation(): ValidationConfig? {
		val hasAlgoConstraints = algoExpirationLevel != null ||
				algoExpirationLevelAfterUpdate != null || algoExpiryOverride.isNotEmpty()
		if (validationPolicy == null && !hasAlgoConstraints) return null
		val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
		val base = AlgorithmConstraintsConfig()
		return ValidationConfig(
			policyType = validationPolicy ?: ValidationConfig().policyType,
			algorithmConstraints = if (hasAlgoConstraints) base.copy(
				expirationLevel = algoExpirationLevel,
				expirationLevelAfterUpdate = algoExpirationLevelAfterUpdate,
				expirationDateOverrides = ConfigSet.parseExpiryOverrides(algoExpiryOverride)
			).stampedToday(today) else base
		)
	}
	
	/**
	 * Build a [TimestampServerConfig] from the provided options.
	 * When [timestampPassword] is supplied it is persisted in the OS keychain under
	 * [ConfigSet.TSA_CREDENTIAL_SERVICE] with the effective username as the account key.
	 * The password itself is never written to the config file.
	 */
	private fun buildTimestampConfig(): TimestampServerConfig? {
		val hasTs = timestampUrl != null || timestampUsername != null ||
				timestampPassword != null || timestampTimeout != null
		if (!hasTs) return null
		
		val effectiveUsername = timestampUsername
		val effectiveCredentialKey = if (timestampPassword != null && effectiveUsername != null) {
			credentialStore.setPassword(ConfigSet.TSA_CREDENTIAL_SERVICE, effectiveUsername, timestampPassword!!)
			effectiveUsername
		} else null
		
		return TimestampServerConfig(
			url = timestampUrl ?: "",
			username = effectiveUsername,
			credentialKey = effectiveCredentialKey,
			timeout = timestampTimeout ?: 30000
		)
	}
}

