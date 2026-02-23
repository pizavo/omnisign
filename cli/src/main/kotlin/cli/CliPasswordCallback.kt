package cz.pizavo.omnisign.cli

import com.github.ajalt.mordant.terminal.Terminal
import cz.pizavo.omnisign.extensions.promptSecret
import cz.pizavo.omnisign.platform.PasswordCallback

/**
 * CLI implementation of [PasswordCallback] backed by a Mordant [Terminal].
 *
 * Delegates to [Terminal.promptSecret], which suppresses echo on any platform that supports it
 * and degrades gracefully when stdin is not a TTY.
 *
 * @param terminal Mordant terminal shared with the rest of the CLI output.
 */
class CliPasswordCallback(private val terminal: Terminal) : PasswordCallback {
    /**
     * Prompt the user for a password/PIN with hidden input.
     *
     * @param prompt Message displayed before the input field.
     * @param title Ignored for CLI; present for interface compatibility.
     * @return The entered value, or `null` if the user cancelled (empty input or EOF).
     */
    override fun requestPassword(prompt: String, title: String): String? =
        terminal.promptSecret(prompt)
}
