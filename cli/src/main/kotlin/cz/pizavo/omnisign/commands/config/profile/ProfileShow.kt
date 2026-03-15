package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand that prints all settings of a single named profile.
 */
class ProfileShow : CliktCommand(name = "show"), KoinComponent {
	private val manageProfile: ManageProfileUseCase by inject()
	private val getConfig: GetConfigUseCase by inject()
	
	private val name by argument(help = "Name of the profile to display (defaults to the active profile)").optional()
	
	override fun help(context: Context): String = "Show all settings of a named profile"
	
	override fun run(): Unit = runBlocking {
		val resolvedName = name ?: run {
			val activeProfile = getConfig().fold(ifLeft = { null }, ifRight = { it.activeProfile })
			if (activeProfile == null) {
				echo("❌ No profile name given and no active profile is set. Use: config profile use <name>", err = true)
				return@runBlocking
			}
			activeProfile
		}
		val activeProfile = getConfig().fold(ifLeft = { null }, ifRight = { it.activeProfile })
		
		manageProfile.get(resolvedName).fold(
			ifLeft = { error -> echo("❌ ${error.message}", err = true) },
			ifRight = { profile ->
				val isActive = activeProfile == profile.name
				val activeLabel = if (isActive) " ◀ active" else ""
				
				echo("═══════════════════════════════════════════════════════════════")
				echo("  PROFILE: ${profile.name}$activeLabel")
				echo("═══════════════════════════════════════════════════════════════")
				
				echo("  Description      : ${profile.description ?: "(none)"}")
				echo("  Hash algorithm   : ${profile.hashAlgorithm ?: "inherit from global"}")
				echo("  Encryption alg.  : ${profile.encryptionAlgorithm ?: "inherit from global"}")
				echo("  Signature level  : ${profile.signatureLevel ?: "inherit from global"}")
				
				echo("\n[Timestamp Server]")
				val tsp = profile.timestampServer
				if (tsp == null) {
					echo("  (inherit from global)")
				} else {
					echo("  URL              : ${tsp.url}")
					echo("  Username         : ${tsp.username ?: "(none)"}")
					echo("  Auth             : ${if (tsp.credentialKey != null) "password stored in keychain" else "none"}")
					echo("  Timeout          : ${tsp.timeout} ms")
				}
				
				echo("\n[OCSP]")
				val ocsp = profile.ocsp
				if (ocsp == null) {
					echo("  (inherit from global)")
				} else {
					echo("  URL              : ${ocsp.url ?: "(none)"}")
				}
				
				echo("\n[CRL]")
				val crl = profile.crl
				if (crl == null) {
					echo("  (inherit from global)")
				} else {
					echo("  Timeout          : ${crl.timeout} ms")
				}
				
				echo("\n[Validation]")
				val validation = profile.validation
				if (validation == null) {
					echo("  (inherit from global)")
				} else {
					echo("  Policy type      : ${validation.policyType}")
					echo("  Custom policy    : ${validation.customPolicyPath ?: "(none)"}")
					echo("  Check revocation : ${validation.checkRevocation}")
					echo("  Use EU LOTL      : ${validation.useEuLotl}")
					if (validation.customTrustedLists.isNotEmpty()) {
						echo("  Custom TLs       :")
						validation.customTrustedLists.forEach { tl ->
							echo("    ● ${tl.name} — ${tl.source}")
						}
					}
					val ac = validation.algorithmConstraints
					echo(
						"  Algo expiry level: ${
							ac.expirationLevel?.toString()
								?: "inherit (default: ${AlgorithmConstraintsConfig.DEFAULT.expirationLevel})"
						}"
					)
					echo(
						"  After update     : ${
							ac.expirationLevelAfterUpdate?.toString()
								?: "inherit (default: ${AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate})"
						}"
					)
					echo("  Policy updated   : ${ac.policyUpdateDate ?: "inherit (DSS default: 2024-10-13)"}")
					if (ac.expirationDateOverrides.isNotEmpty()) {
						echo("  Expiry overrides :")
						ac.expirationDateOverrides.forEach { (alg, date) -> echo("    $alg → $date") }
					}
				}
				
				echo("\n[Algorithm Restrictions]")
				if (profile.disabledHashAlgorithms.isEmpty()) {
					echo("  Disabled hash    : (none beyond global)")
				} else {
					echo("  Disabled hash    : ${profile.disabledHashAlgorithms.joinToString { it.name }}")
				}
				if (profile.disabledEncryptionAlgorithms.isEmpty()) {
					echo("  Disabled enc.    : (none beyond global)")
				} else {
					echo("  Disabled enc.    : ${profile.disabledEncryptionAlgorithms.joinToString { it.name }}")
				}
			}
		)
	}
}


