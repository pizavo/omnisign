package cz.pizavo.omnisign.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.data.repository.FileConfigRepository

/**
 * CLI subcommand that prints the resolved configuration file path.
 *
 * Useful for debugging or scripting to find where OmniSign stores its configuration
 * on the current platform (Windows `%APPDATA%`, macOS `~/Library/Application Support`,
 * Linux `~/.config`).
 */
class ConfigPath : CliktCommand(name = "path") {
	override fun help(context: Context): String =
		"Show the configuration file path"
	
	override fun run() {
		echo(FileConfigRepository.getDefaultConfigPath().toAbsolutePath().toString())
	}
}

