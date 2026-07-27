package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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
				error.details?.let { echo("Details: $it", err = true) }
				error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
			},
			ifRight = {
				echo("✅ Profile '$name' removed.")
			}
		)
	}
}

