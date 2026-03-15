package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
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

