package cz.pizavo.omnisign.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import cz.pizavo.omnisign.domain.model.config.OperationConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig

/**
 * Reusable Clikt option group for on-execute configuration overrides.
 * Any command can include this group to allow the user to override config settings
 * at runtime without modifying the stored configuration.
 *
 * TSA credentials supplied here are held in memory only for the duration of the
 * current invocation and are never written to any file or the OS keychain.
 * To persist credentials permanently use `config set --timestamp-password`.
 */
class OperationConfigOptions : OptionGroup(
	name = "Config overrides",
	help = "Override configuration settings for this single execution"
) {
	/** Override the hash algorithm used for this operation. */
	val hashAlgorithm by option(
		"--hash-algorithm", "-H",
		help = "Hash algorithm override (${HashAlgorithm.entries.joinToString { it.name }})"
	).enum<HashAlgorithm>()
	
	/** Override the encryption (signing key) algorithm used for this operation. */
	val encryptionAlgorithm by option(
		"--encryption-algorithm", "-E",
		help = "Encryption algorithm override (${EncryptionAlgorithm.entries.joinToString { it.name }})"
	).enum<EncryptionAlgorithm>()
	
	/** Override the signature level for this operation. */
	val signatureLevel by option(
		"--signature-level", "-L",
		help = "Signature level override (${SignatureLevel.entries.joinToString { it.name }})"
	).enum<SignatureLevel>()
	
	/** Override the timestamp server URL for this operation. */
	val timestampUrl by option(
		"--timestamp-url",
		help = "Timestamp server URL override"
	)
	
	/** Override the timestamp server HTTP Basic username for this operation. */
	val timestampUsername by option(
		"--timestamp-username",
		help = "Timestamp server HTTP Basic username override"
	)
	
	/**
	 * Override the timestamp server HTTP Basic password for this operation.
	 * This value is used in-memory only and is never written to disk or the OS keychain.
	 * To persist credentials permanently use `config set --timestamp-password`.
	 */
	val timestampPassword by option(
		"--timestamp-password",
		help = "Timestamp server HTTP Basic password override (in-memory only, not persisted)"
	)
	
	/** Override the timestamp server request timeout (milliseconds) for this operation. */
	val timestampTimeout by option(
		"--timestamp-timeout",
		help = "Timestamp server request timeout override in milliseconds"
	).int()
	
	/** Override the validation policy type for this operation. */
	val validationPolicy by option(
		"--validation-policy",
		help = "Validation policy type override (${ValidationPolicyType.entries.joinToString { it.name }})"
	).enum<ValidationPolicyType>()
	
	/**
	 * When set, custom trusted lists from the global config are excluded from the
	 * resolved trusted list set for this operation.  Only profile-level and
	 * operation-level TLs remain active.  Has no effect when no profile is in use.
	 */
	val noGlobalTls by option(
		"--no-global-tls",
		help = "Exclude global custom trusted lists — use only the active profile's TLs"
	).flag(default = false)
	
	/** Hash algorithms additionally disabled for this single operation. */
	val disableHashAlgorithm by option(
		"--disable-hash-algorithm",
		help = "Disable a hash algorithm for this operation only. Repeatable."
	).enum<HashAlgorithm>().multiple()
	
	/** Encryption algorithms additionally disabled for this single operation. */
	val disableEncryptionAlgorithm by option(
		"--disable-encryption-algorithm",
		help = "Disable an encryption algorithm for this operation only. Repeatable."
	).enum<EncryptionAlgorithm>().multiple()
	
	/**
	 * Build an [OperationConfig] from the provided CLI options.
	 * Only non-null values are included; null means "use the resolved default".
	 * When any timestamp option is supplied a [TimestampServerConfig] override is
	 * constructed. A blank override URL falls back to the stored URL during
	 * [cz.pizavo.omnisign.domain.model.config.ResolvedConfig.resolve].
	 * The runtime [timestampPassword] is carried in the override object but
	 * is never written to any persistent store.
	 */
	fun toOperationConfig(): OperationConfig {
		val hasTimestampOverride = timestampUrl != null || timestampUsername != null ||
				timestampPassword != null || timestampTimeout != null
		
		val tsConfig = if (hasTimestampOverride) {
			TimestampServerConfig(
				url = timestampUrl ?: "",
				username = timestampUsername,
				runtimePassword = timestampPassword,
				timeout = timestampTimeout ?: 30000
			)
		} else null
		
		return OperationConfig(
			hashAlgorithm = hashAlgorithm,
			encryptionAlgorithm = encryptionAlgorithm,
			signatureLevel = signatureLevel,
			timestampServer = tsConfig,
			validation = validationPolicy?.let { ValidationConfig(policyType = it) },
			disabledHashAlgorithms = disableHashAlgorithm.toSet(),
			disabledEncryptionAlgorithms = disableEncryptionAlgorithm.toSet()
		)
	}
}
