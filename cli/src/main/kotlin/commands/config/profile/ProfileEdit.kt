package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
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
 * CLI subcommand for editing an existing named profile.
 *
 * Only the options that are explicitly supplied are updated; all others retain their
 * current values.  Use the corresponding [--clear-*] flags to explicitly unset
 * an optional field.
 *
 * The TSA password is stored in the OS-native credential store (keychain) and
 * never written to the plain-text config file.
 */
class ProfileEdit : CliktCommand(name = "edit"), KoinComponent {
	private val manageProfile: ManageProfileUseCase by inject()
	private val credentialStore: CredentialStore by inject()
	
	private val name by argument(help = "Name of the profile to edit")
	private val description by option("--description", "-d", help = "New profile description")
	private val clearDescription by option(
		"--clear-description",
		help = "Remove the profile description"
	).flag(default = false)
	private val hashAlgorithm by option(
		"--hash-algorithm", "-H",
		help = "New hash algorithm override"
	).enum<HashAlgorithm>()
	private val clearHashAlgorithm by option(
		"--clear-hash-algorithm",
		help = "Remove the hash algorithm override (fall back to global)"
	).flag(default = false)
	private val encryptionAlgorithm by option(
		"--encryption-algorithm", "-E",
		help = "New encryption (signing key) algorithm override"
	).enum<EncryptionAlgorithm>()
	private val clearEncryptionAlgorithm by option(
		"--clear-encryption-algorithm",
		help = "Remove the encryption algorithm override (fall back to global)"
	).flag(default = false)
	private val signatureLevel by option(
		"--signature-level", "-L",
		help = "New signature level override"
	).enum<SignatureLevel>()
	private val clearSignatureLevel by option(
		"--clear-signature-level",
		help = "Remove the signature level override (fall back to global)"
	).flag(default = false)
	private val timestampUrl by option("--timestamp-url", help = "New timestamp server URL")
	private val timestampUsername by option(
		"--timestamp-username",
		help = "New timestamp server HTTP Basic username"
	)
	private val timestampPassword by option(
		"--timestamp-password",
		help = "New timestamp server HTTP Basic password (stored in OS keychain, not in config file)"
	)
	private val timestampTimeout by option(
		"--timestamp-timeout",
		help = "New timestamp server request timeout in milliseconds"
	).int()
	private val clearTimestamp by option(
		"--clear-timestamp",
		help = "Remove the entire timestamp server configuration from this profile"
	).flag(default = false)
	private val validationPolicy by option(
		"--validation-policy",
		help = "New validation policy type override"
	).enum<ValidationPolicyType>()
	private val clearValidationPolicy by option(
		"--clear-validation-policy",
		help = "Remove the validation policy override (fall back to global)"
	).flag(default = false)
	private val algoExpirationLevel by option(
		"--algo-expiration-level",
		help = "Algorithm expiration severity (${AlgorithmConstraintLevel.entries.joinToString { it.name }})"
	).enum<AlgorithmConstraintLevel>()
	private val algoExpirationLevelAfterUpdate by option(
		"--algo-expiration-level-after-update",
		help = "Algorithm expiration severity after policy update date " +
				"(${AlgorithmConstraintLevel.entries.joinToString { it.name }})"
	).enum<AlgorithmConstraintLevel>()
	private val clearAlgoConstraints by option(
		"--clear-algo-constraints",
		help = "Reset all algorithm constraint overrides to defaults for this profile"
	).flag(default = false)
	private val algoExpiryOverride by option(
		"--algo-expiry-override",
		help = "Per-algorithm expiration date override: ALGORITHM=DATE (e.g. RIPEMD160=2030-01-01). Repeatable."
	).multiple()
	private val clearAlgoExpiryOverrides by option(
		"--clear-algo-expiry-overrides",
		help = "Remove all per-algorithm expiration date overrides from this profile"
	).flag(default = false)
	
	private val disableHashAlgorithm by option(
		"--disable-hash-algorithm",
		help = "Add a hash algorithm to this profile's disabled set. Repeatable."
	).enum<HashAlgorithm>().multiple()
	
	private val enableHashAlgorithm by option(
		"--enable-hash-algorithm",
		help = "Remove a hash algorithm from this profile's disabled set. Repeatable."
	).enum<HashAlgorithm>().multiple()
	
	private val disableEncryptionAlgorithm by option(
		"--disable-encryption-algorithm",
		help = "Add an encryption algorithm to this profile's disabled set. Repeatable."
	).enum<EncryptionAlgorithm>().multiple()
	
	private val enableEncryptionAlgorithm by option(
		"--enable-encryption-algorithm",
		help = "Remove an encryption algorithm from this profile's disabled set. Repeatable."
	).enum<EncryptionAlgorithm>().multiple()
	
	override fun help(context: Context): String =
		"Edit an existing configuration profile (only supplied options are changed)"
	
	override fun run(): Unit = runBlocking {
		manageProfile.get(name).fold(
			ifLeft = { error ->
				echo("❌ ${error.message}", err = true)
			},
			ifRight = { existing ->
				val updated = buildPatchedProfile(existing)
				manageProfile.upsert(updated).fold(
					ifLeft = { error ->
						echo("❌ Failed to save profile: ${error.message}", err = true)
						error.details?.let { echo("Details: $it", err = true) }
					},
					ifRight = {
						echo("✅ Profile '$name' updated.")
					}
				)
			}
		)
	}
	
	/**
	 * Merge the supplied options onto [existing], leaving untouched fields intact.
	 */
	@Suppress("CyclomaticComplexMethod")
	private fun buildPatchedProfile(existing: ProfileConfig): ProfileConfig {
		val patchedTs = buildPatchedTimestampConfig(existing.timestampServer)
		return existing.copy(
			description = when {
				clearDescription -> null
				description != null -> description
				else -> existing.description
			},
			hashAlgorithm = patchHashAlgorithm(existing),
			encryptionAlgorithm = patchEncryptionAlgorithm(existing),
			signatureLevel = patchSignatureLevel(existing),
			timestampServer = patchedTs,
			disabledHashAlgorithms = (existing.disabledHashAlgorithms + disableHashAlgorithm) - enableHashAlgorithm.toSet(),
			disabledEncryptionAlgorithms = (existing.disabledEncryptionAlgorithms + disableEncryptionAlgorithm) - enableEncryptionAlgorithm.toSet(),
			validation = patchValidation(existing)
		)
	}
	
	/**
	 * Resolve the patched hash algorithm field.
	 */
	private fun patchHashAlgorithm(existing: ProfileConfig) = when {
		clearHashAlgorithm -> null
		hashAlgorithm != null -> hashAlgorithm
		else -> existing.hashAlgorithm
	}
	
	/**
	 * Resolve the patched encryption algorithm field: clear it when requested,
	 * set a new value when provided, or keep the existing one.
	 */
	private fun patchEncryptionAlgorithm(existing: ProfileConfig) = when {
		clearEncryptionAlgorithm -> null
		encryptionAlgorithm != null -> encryptionAlgorithm
		else -> existing.encryptionAlgorithm
	}
	
	/**
	 * Resolve the patched signature level field.
	 */
	private fun patchSignatureLevel(existing: ProfileConfig) = when {
		clearSignatureLevel -> null
		signatureLevel != null -> signatureLevel
		else -> existing.signatureLevel
	}
	
	/**
	 * Resolve the patched validation config field, merging any algo constraint options
	 * onto the existing value.
	 */
	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun patchValidation(existing: ProfileConfig): ValidationConfig? {
		val hasAlgoConstraints = algoExpirationLevel != null ||
				algoExpirationLevelAfterUpdate != null ||
				algoExpiryOverride.isNotEmpty() || clearAlgoExpiryOverrides
		
		if (clearValidationPolicy && !hasAlgoConstraints && !clearAlgoConstraints) return null
		if (validationPolicy == null && !hasAlgoConstraints && !clearAlgoConstraints) {
			return existing.validation
		}
		
		val base = existing.validation ?: ValidationConfig()
		return base.copy(
			policyType = when {
				clearValidationPolicy -> ValidationConfig().policyType
				validationPolicy != null -> validationPolicy!!
				else -> base.policyType
			},
			algorithmConstraints = patchAlgorithmConstraints(base)
		)
	}
	
	/**
	 * Resolve the patched [AlgorithmConstraintsConfig] — reset to defaults when
	 * [clearAlgoConstraints] is set, otherwise merge the provided options onto the existing value.
	 */
	private fun patchAlgorithmConstraints(base: ValidationConfig): AlgorithmConstraintsConfig {
		if (clearAlgoConstraints) return AlgorithmConstraintsConfig().stampedToday(
			Clock.System.todayIn(TimeZone.currentSystemDefault())
		)
		val existing = base.algorithmConstraints
		val updatedOverrides = when {
			clearAlgoExpiryOverrides -> emptyMap()
			algoExpiryOverride.isNotEmpty() ->
				existing.expirationDateOverrides + ConfigSet.parseExpiryOverrides(algoExpiryOverride)
			
			else -> existing.expirationDateOverrides
		}
		return existing.copy(
			expirationLevel = algoExpirationLevel ?: existing.expirationLevel,
			expirationLevelAfterUpdate = algoExpirationLevelAfterUpdate
				?: existing.expirationLevelAfterUpdate,
			expirationDateOverrides = updatedOverrides
		).stampedToday(Clock.System.todayIn(TimeZone.currentSystemDefault()))
	}
	
	/**
	 * Patch [existing] timestamp config with the supplied options.
	 * When [clearTimestamp] is set the entire TSA block is removed.
	 * When [timestampPassword] is supplied it is persisted in the OS keychain under
	 * [ConfigSet.TSA_CREDENTIAL_SERVICE] with the effective username as the account key.
	 */
	private fun buildPatchedTimestampConfig(existing: TimestampServerConfig?): TimestampServerConfig? {
		val anyTsOption = timestampUrl != null || timestampUsername != null ||
				timestampPassword != null || timestampTimeout != null
		if (clearTimestamp || (!anyTsOption && existing == null)) return null
		if (!anyTsOption) return existing
		
		val baseUrl = timestampUrl ?: existing?.url ?: ""
		val effectiveUsername = timestampUsername ?: existing?.username
		val effectiveCredentialKey = if (timestampPassword != null && effectiveUsername != null) {
			credentialStore.setPassword(ConfigSet.TSA_CREDENTIAL_SERVICE, effectiveUsername, timestampPassword!!)
			effectiveUsername
		} else {
			existing?.credentialKey
		}
		
		return TimestampServerConfig(
			url = baseUrl,
			username = effectiveUsername,
			credentialKey = effectiveCredentialKey,
			timeout = timestampTimeout ?: existing?.timeout ?: 30000
		)
	}
}

