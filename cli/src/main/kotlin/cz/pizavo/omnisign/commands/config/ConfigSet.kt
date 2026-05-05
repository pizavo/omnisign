package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import cz.pizavo.omnisign.cli.resolvePasswordOption
import cz.pizavo.omnisign.commands.config.ConfigSet.Companion.TSA_CREDENTIAL_SERVICE
import cz.pizavo.omnisign.domain.model.config.enums.*
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import cz.pizavo.omnisign.platform.PasswordCallback
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

/**
 * CLI subcommand for modifying the global (default) configuration.
 * Only the provided options will be changed; omitted options remain unchanged.
 *
 * The TSA password is stored in the OS-native credential store (keychain) and
 * never written to the plain-text config file.
 */
class ConfigSet : CliktCommand(name = "set"), KoinComponent {
	private val setGlobalConfig: SetGlobalConfigUseCase by inject()
	private val credentialStore: CredentialStore by inject()
	private val passwordCallback: PasswordCallback by inject()
	
	private val hashAlgorithm by option(
		"--hash-algorithm", "-H",
		help = "Default hash algorithm (${HashAlgorithm.entries.joinToString { it.name }})"
	).enum<HashAlgorithm>()
	
	private val encryptionAlgorithm by option(
		"--encryption-algorithm", "-E",
		help = "Default encryption algorithm (${EncryptionAlgorithm.entries.joinToString { it.name }})"
	).enum<EncryptionAlgorithm>()
	
	private val signatureLevel by option(
		"--signature-level", "-L",
		help = "Default signature level (${SignatureLevel.entries.joinToString { it.name }})"
	).enum<SignatureLevel>()
	
	private val timestampUrl by option(
		"--timestamp-url",
		help = "Default timestamp server URL"
	)
	
	private val timestampUsername by option(
		"--timestamp-username",
		help = "Default timestamp server HTTP Basic username"
	)
	
	private val timestampPassword by option(
		"--timestamp-password",
		help = "Default timestamp server HTTP Basic password (stored in OS keychain; use '-' to prompt with hidden input)"
	)
	
	private val timestampTimeout by option(
		"--timestamp-timeout",
		help = "Default timestamp server request timeout in milliseconds"
	).int()
	
	private val validationPolicy by option(
		"--validation-policy",
		help = "Default validation policy (${ValidationPolicyType.entries.joinToString { it.name }})"
	).enum<ValidationPolicyType>()
	
	private val checkRevocation by option(
		"--check-revocation",
		help = "Check certificate revocation status (true/false)"
	)
	
	private val useEuLotl by option(
		"--use-eu-lotl",
		help = "Use EU List of Trusted Lists for validation (true/false)"
	)
	
	private val algoExpirationLevel by option(
		"--algo-expiration-level",
		help = "Severity when an algorithm's expiration date has passed " +
				"(${AlgorithmConstraintLevel.entries.joinToString { it.name }})"
	).enum<AlgorithmConstraintLevel>()
	
	private val algoExpirationLevelAfterUpdate by option(
		"--algo-expiration-level-after-update",
		help = "Severity after the policy update date " +
				"(${AlgorithmConstraintLevel.entries.joinToString { it.name }})"
	).enum<AlgorithmConstraintLevel>()
	
	private val algoExpiryOverride by option(
		"--algo-expiry-override",
		help = "Per-algorithm expiration date override in ALGORITHM=DATE format " +
				"(e.g. RIPEMD160=2030-01-01). Repeatable. Overrides the bundled DSS date " +
				"for both pre-signing checks and DSS validation."
	).multiple()
	
	private val disableHashAlgorithm by option(
		"--disable-hash-algorithm",
		help = "Globally disable a hash algorithm so it cannot be selected at any level. Repeatable."
	).enum<HashAlgorithm>().multiple()
	
	private val enableHashAlgorithm by option(
		"--enable-hash-algorithm",
		help = "Re-enable a globally disabled hash algorithm. Repeatable."
	).enum<HashAlgorithm>().multiple()
	
	private val disableEncryptionAlgorithm by option(
		"--disable-encryption-algorithm",
		help = "Globally disable an encryption algorithm so it cannot be selected at any level. Repeatable."
	).enum<EncryptionAlgorithm>().multiple()
	
	private val enableEncryptionAlgorithm by option(
		"--enable-encryption-algorithm",
		help = "Re-enable a globally disabled encryption algorithm. Repeatable."
	).enum<EncryptionAlgorithm>().multiple()

	private val pkcs11ProbeTimeout by option(
		"--pkcs11-probe-timeout",
		help = "Maximum seconds to wait for a single PKCS#11 library probe (1–120)"
	).int()
	
	override fun help(context: Context): String =
		"Set global configuration defaults"
	
	override fun run(): Unit = runBlocking {
		if (listOf(
				hashAlgorithm, encryptionAlgorithm, signatureLevel, timestampUrl, timestampUsername,
				timestampPassword, timestampTimeout, validationPolicy, checkRevocation, useEuLotl,
				algoExpirationLevel, algoExpirationLevelAfterUpdate, pkcs11ProbeTimeout
			)
				.all { it == null } && algoExpiryOverride.isEmpty()
				&& disableHashAlgorithm.isEmpty() && enableHashAlgorithm.isEmpty()
				&& disableEncryptionAlgorithm.isEmpty() && enableEncryptionAlgorithm.isEmpty()
		) {
			echo("No options provided. Run 'config set --help' for available options.")
			return@runBlocking
		}
		
		val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
		val result = setGlobalConfig {
			val hasAlgoChange = algoExpirationLevel != null || algoExpirationLevelAfterUpdate != null ||
					algoExpiryOverride.isNotEmpty()
			copy(
				defaultHashAlgorithm = hashAlgorithm ?: defaultHashAlgorithm,
				defaultEncryptionAlgorithm = encryptionAlgorithm ?: defaultEncryptionAlgorithm,
				defaultSignatureLevel = signatureLevel ?: defaultSignatureLevel,
				timestampServer = buildTimestampConfig(this.timestampServer),
				disabledHashAlgorithms = (disabledHashAlgorithms + disableHashAlgorithm) - enableHashAlgorithm.toSet(),
				disabledEncryptionAlgorithms = (disabledEncryptionAlgorithms + disableEncryptionAlgorithm) - enableEncryptionAlgorithm.toSet(),
				pkcs11ProbeTimeoutSeconds = pkcs11ProbeTimeout?.toLong()?.coerceIn(1, 120)
					?: pkcs11ProbeTimeoutSeconds,
				validation = validation.copy(
					useEuLotl = useEuLotl?.toBooleanStrictOrNull() ?: validation.useEuLotl,
					algorithmConstraints = validation.algorithmConstraints.copy(
						expirationLevel = algoExpirationLevel
							?: validation.algorithmConstraints.expirationLevel,
						expirationLevelAfterUpdate = algoExpirationLevelAfterUpdate
							?: validation.algorithmConstraints.expirationLevelAfterUpdate,
						expirationDateOverrides = validation.algorithmConstraints.expirationDateOverrides
								+ parseExpiryOverrides(algoExpiryOverride)
					).let { if (hasAlgoChange) it.stampedToday(today) else it })
			)
		}
		result.fold(
			ifLeft = { error ->
				echo("❌ Failed to save configuration: ${error.message}", err = true)
				if (error.details != null) echo("Details: ${error.details}", err = true)
				throw ProgramResult(1)
			},
			ifRight = {
				echo("✅ Global configuration updated.")
			}
		)
	}
	
	/**
	 * Build the updated [TimestampServerConfig], merging CLI options onto [existing].
	 * When [timestampPassword] is supplied it is persisted in the OS keychain under
	 * the service name [TSA_CREDENTIAL_SERVICE] and the effective username as account.
	 * The password itself is never written to the config file.
	 * Passing `"-"` as the password triggers an interactive hidden-input prompt via [passwordCallback].
	 */
	private fun buildTimestampConfig(existing: TimestampServerConfig?): TimestampServerConfig? {
		val resolvedPassword = resolvePasswordOption(timestampPassword, passwordCallback)
		val hasUpdate = timestampUrl != null || timestampUsername != null ||
				resolvedPassword != null || timestampTimeout != null
		if (!hasUpdate) return existing
		
		val base = existing ?: TimestampServerConfig(url = "")
		val effectiveUsername = timestampUsername ?: base.username
		val effectiveCredentialKey = if (resolvedPassword != null && effectiveUsername != null) {
			credentialStore.setPassword(TSA_CREDENTIAL_SERVICE, effectiveUsername, resolvedPassword)
			effectiveUsername
		} else {
			base.credentialKey
		}
		
		return base.copy(
			url = timestampUrl ?: base.url,
			username = effectiveUsername,
			credentialKey = effectiveCredentialKey,
			timeout = timestampTimeout ?: base.timeout
		)
	}
	
	companion object {
		/** Keychain service name used as the namespace for all TSA passwords. */
		const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
		
		/**
		 * Parse a list of `ALGORITHM=DATE` strings into a map suitable for
		 * [cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig.expirationDateOverrides].
		 * Entries that do not contain `=` are silently ignored.
		 */
		fun parseExpiryOverrides(raw: List<String>): Map<String, String> =
			raw.mapNotNull { entry ->
				val idx = entry.indexOf('=')
				if (idx < 1) null else entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
			}.toMap()
	}
}

