package cz.pizavo.omnisign.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.SetGlobalConfigUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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

    private val hashAlgorithm by option(
        "--hash-algorithm", "-H",
        help = "Default hash algorithm (${HashAlgorithm.entries.joinToString { it.name }})"
    ).enum<HashAlgorithm>()

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
        help = "Default timestamp server HTTP Basic password (stored in OS keychain, not in config file)"
    )

    private val timestampTimeout by option(
        "--timestamp-timeout",
        help = "Default timestamp server request timeout in milliseconds"
    ).int()

    private val ocspUrl by option(
        "--ocsp-url",
        help = "Default OCSP URL"
    )

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

    override fun help(context: Context): String =
        "Set global configuration defaults"

    override fun run(): Unit = runBlocking {
        if (listOf(hashAlgorithm, signatureLevel, timestampUrl, timestampUsername,
                timestampPassword, timestampTimeout, ocspUrl,
                validationPolicy, checkRevocation, useEuLotl).all { it == null }
        ) {
            echo("No options provided. Run 'config set --help' for available options.")
            return@runBlocking
        }

        setGlobalConfig {
            copy(
                defaultHashAlgorithm = hashAlgorithm ?: defaultHashAlgorithm,
                defaultSignatureLevel = signatureLevel ?: defaultSignatureLevel,
                timestampServer = buildTimestampConfig(this.timestampServer),
                ocsp = ocspUrl?.let { ocsp.copy(url = it) } ?: ocsp,
                validation = validation.copy(
                    policyType = validationPolicy ?: validation.policyType,
                    checkRevocation = checkRevocation?.toBooleanStrictOrNull() ?: validation.checkRevocation,
                    useEuLotl = useEuLotl?.toBooleanStrictOrNull() ?: validation.useEuLotl
                )
            )
        }.fold(
            ifLeft = { error ->
                echo("❌ Failed to save configuration: ${error.message}", err = true)
                error.details?.let { echo("Details: $it", err = true) }
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
     */
    private fun buildTimestampConfig(existing: TimestampServerConfig?): TimestampServerConfig? {
        val hasUpdate = timestampUrl != null || timestampUsername != null ||
            timestampPassword != null || timestampTimeout != null
        if (!hasUpdate) return existing

        val base = existing ?: TimestampServerConfig(url = "")
        val effectiveUsername = timestampUsername ?: base.username
        val effectiveCredentialKey = if (timestampPassword != null && effectiveUsername != null) {
            credentialStore.setPassword(TSA_CREDENTIAL_SERVICE, effectiveUsername, timestampPassword!!)
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
    }
}



