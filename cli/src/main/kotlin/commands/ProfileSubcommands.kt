package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.ValidationConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand for listing all named profiles.
 */
class ProfileList : CliktCommand(name = "list"), KoinComponent {
    private val manageProfile: ManageProfileUseCase by inject()

    override fun help(context: Context): String = "List all configuration profiles"

    override fun run(): Unit = runBlocking {
        manageProfile.list().fold(
            ifLeft = { error ->
                echo("❌ ${error.message}", err = true)
            },
            ifRight = { profiles ->
                if (profiles.isEmpty()) {
                    echo("No profiles defined. Create one with: config profile create <name>")
                } else {
                    echo("Profiles:")
                    profiles.forEach { (name, profile) ->
                        echo("  ● $name${profile.description?.let { " — $it" } ?: ""}")
                    }
                }
            }
        )
    }
}

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

    override fun help(context: Context): String =
        "Create or update a named configuration profile"

    override fun run(): Unit = runBlocking {
        val tsConfig = buildTimestampConfig()
        val profile = ProfileConfig(
            name = name,
            description = description,
            hashAlgorithm = hashAlgorithm,
            signatureLevel = signatureLevel,
            timestampServer = tsConfig,
            validation = validationPolicy?.let { ValidationConfig(policyType = it) }
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
    private fun buildPatchedProfile(existing: ProfileConfig): ProfileConfig {
        val patchedTs = buildPatchedTimestampConfig(existing.timestampServer)
        return existing.copy(
            description = when {
                clearDescription -> null
                description != null -> description
                else -> existing.description
            },
            hashAlgorithm = when {
                clearHashAlgorithm -> null
                hashAlgorithm != null -> hashAlgorithm
                else -> existing.hashAlgorithm
            },
            signatureLevel = when {
                clearSignatureLevel -> null
                signatureLevel != null -> signatureLevel
                else -> existing.signatureLevel
            },
            timestampServer = patchedTs,
            validation = when {
                clearValidationPolicy -> null
                validationPolicy != null -> ValidationConfig(policyType = validationPolicy!!)
                else -> existing.validation
            }
        )
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

/**
 * CLI subcommand for activating a named profile as the default.
 */
class ProfileUse : CliktCommand(name = "use"), KoinComponent {
    private val manageProfile: ManageProfileUseCase by inject()

    private val name by argument(help = "Profile name to activate (use 'none' to clear)")

    override fun help(context: Context): String = "Set the active profile"

    override fun run(): Unit = runBlocking {
        val target = if (name.lowercase() == "none") null else name
        manageProfile.setActive(target).fold(
            ifLeft = { error ->
                echo("❌ ${error.message}", err = true)
            },
            ifRight = {
                if (target == null) {
                    echo("✅ Active profile cleared.")
                } else {
                    echo("✅ Active profile set to '$target'.")
                }
            }
        )
    }
}

/**
 * CLI subcommand for removing a named profile.
 */
class ProfileRemove : CliktCommand(name = "remove"), KoinComponent {
    private val manageProfile: ManageProfileUseCase by inject()

    private val name by argument(help = "Profile name to remove")

    override fun help(context: Context): String = "Remove a named profile"

    override fun run(): Unit = runBlocking {
        manageProfile.remove(name).fold(
            ifLeft = { error ->
                echo("❌ ${error.message}", err = true)
            },
            ifRight = {
                echo("✅ Profile '$name' removed.")
            }
        )
    }
}

