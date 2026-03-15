package cz.pizavo.omnisign.commands.config.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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

