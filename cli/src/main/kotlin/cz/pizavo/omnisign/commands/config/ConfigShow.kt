package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand that prints the current configuration in a human-readable format.
 */
class ConfigShow : CliktCommand(name = "show"), KoinComponent {
	private val getConfig: GetConfigUseCase by inject()
	
	override fun help(context: Context): String =
		"Show the current application configuration"
	
	override fun run(): Unit = runBlocking {
		getConfig().fold(
			ifLeft = { error ->
				echo("❌ Failed to load configuration: ${error.message}", err = true)
				if (error.details != null) echo("Details: ${error.details}", err = true)
				throw ProgramResult(1)
			},
			ifRight = { config ->
				echo("═══════════════════════════════════════════════════════════════")
				echo("                    APPLICATION CONFIGURATION")
				echo("═══════════════════════════════════════════════════════════════")
				echo("\n[Global]")
				echo("  Default hash algorithm      : ${config.global.defaultHashAlgorithm}")
				echo("  Default encryption algorithm: ${config.global.defaultEncryptionAlgorithm ?: "infer from key"}")
				echo("  Default signature level     : ${config.global.defaultSignatureLevel}")
				val tsp = config.global.timestampServer
				echo("  Timestamp server       : ${tsp?.url ?: "not set"}")
				echo("  OCSP URL               : ${config.global.ocsp.url ?: "not set"}")
				echo("  CRL timeout            : ${config.global.crl.timeout} ms")
				echo("  Check revocation       : ${config.global.validation.checkRevocation}")
				echo("  Validation policy      : ${config.global.validation.policyType}")
				echo("  Use EU LOTL            : ${config.global.validation.useEuLotl}")
				val ac = config.global.validation.algorithmConstraints
				echo("  Algo expiry level      : ${ac.expirationLevel?.toString()
					?: "default (${AlgorithmConstraintsConfig.DEFAULT.expirationLevel})"}")
				echo("  Algo expiry after upd. : ${ac.expirationLevelAfterUpdate?.toString()
					?: "default (${AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate})"}")
				echo("  Algo policy updated    : ${ac.policyUpdateDate ?: "DSS default (2024-10-13)"}")
				if (ac.expirationDateOverrides.isNotEmpty()) {
					echo("  Algo expiry overrides  :")
					ac.expirationDateOverrides.forEach { (alg, date) -> echo("    $alg → $date") }
				}
				if (config.global.disabledHashAlgorithms.isNotEmpty()) {
					echo("  Disabled hash algs     : ${config.global.disabledHashAlgorithms.joinToString { it.name }}")
				}
				if (config.global.disabledEncryptionAlgorithms.isNotEmpty()) {
					echo("  Disabled enc algs      : ${config.global.disabledEncryptionAlgorithms.joinToString { it.name }}")
				}
				
				echo("\n[Active profile]")
				val activeProfile = config.activeProfile
				echo("  ${activeProfile ?: "none"}")
				
				if (config.profiles.isEmpty()) {
					echo("\n[Profiles]\n  (none)")
				} else {
					echo("\n[Profiles]")
					config.profiles.forEach { (name, profile) ->
						val active = if (name == activeProfile) " ◀ active" else ""
						echo("  ● $name$active")
						profile.description?.let { echo("    Description  : $it") }
						profile.hashAlgorithm?.let { echo("    Hash alg       : $it") }
						profile.encryptionAlgorithm?.let { echo("    Encryption alg : $it") }
						profile.signatureLevel?.let { echo("    Sig level      : $it") }
						profile.timestampServer?.let { echo("    Timestamp    : ${it.url}") }
						profile.validation?.let {
							echo("    Val policy     : ${it.policyType}")
							val ac = it.algorithmConstraints
							echo("    Algo expiry    : ${ac.expirationLevel?.toString() ?: "inherit"}")
							echo("    After update   : ${ac.expirationLevelAfterUpdate?.toString() ?: "inherit"}")
							ac.policyUpdateDate?.let { d -> echo("    Algo updated   : $d") }
							if (ac.expirationDateOverrides.isNotEmpty()) {
								echo("    Expiry overrides:")
								ac.expirationDateOverrides.forEach { (alg, date) ->
									echo("      $alg → $date")
								}
							}
						}
						if (profile.disabledHashAlgorithms.isNotEmpty()) {
							echo("    Disabled hash  : ${profile.disabledHashAlgorithms.joinToString { it.name }}")
						}
						if (profile.disabledEncryptionAlgorithms.isNotEmpty()) {
							echo("    Disabled enc   : ${profile.disabledEncryptionAlgorithms.joinToString { it.name }}")
						}
					}
				}
				if (config.global.customPkcs11Libraries.isEmpty()) {
					echo("\n[Custom PKCS#11 Libraries]\n  (none — add with: config pkcs11 add --name <label> --path <path>)")
				} else {
					echo("\n[Custom PKCS#11 Libraries]")
					config.global.customPkcs11Libraries.forEach { lib ->
						val status = if (File(lib.path).exists()) "✅" else "⚠️  (file not found)"
						echo("  ● ${lib.name}  $status")
						echo("    Path: ${lib.path}")
					}
				}
				echo("\n═══════════════════════════════════════════════════════════════")
			}
		)
	}
}

