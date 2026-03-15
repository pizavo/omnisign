package cz.pizavo.omnisign.extensions

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt
import com.github.ajalt.mordant.terminal.prompt
import java.io.File

/**
 * Prompt for a required non-blank string, re-asking until input is provided.
 */
internal fun Terminal.promptRequired(text: String, hint: String? = null, default: String? = null): String {
	hint?.let { println("   $it") }
	
	while (true) {
		val answer = prompt(text, default = default) ?: ""
		if (answer.isNotBlank()) return answer
		println("   ⚠️ This field is required.")
	}
}

/**
 * Prompt for an optional string; returns null when the user submits blank input.
 */
internal fun Terminal.promptOptional(text: String, hint: String? = null): String? {
	hint?.let { println("   $it") }
	
	return prompt(text, default = "").takeIf { it?.isNotBlank() == true }
}

/**
 * Display a numbered pick-list of [hints] and return the chosen URI.
 * Accepts either a list index or a custom URI typed in full.
 */
internal fun Terminal.promptUriWithHints(label: String, hints: List<Pair<String, String>>): String {
	println("\n   Common $label values (enter a number or type your own URI):")
	hints.forEachIndexed { i, (_, desc) -> println("   ${i + 1}. $desc") }
	println("   ──")
	
	while (true) {
		val input = prompt("   $label") ?: ""
		val idx = input.trim().toIntOrNull()
		if (idx != null && idx in 1..hints.size) return hints[idx - 1].first
		if (input.startsWith("http")) return input.trim()
		
		println("   ⚠️ Enter a number from the list, or a full URI starting with http.")
	}
}

/**
 * Prompt for a certificate file path, validating existence and readability.
 */
internal fun Terminal.promptCertPath(): String {
	while (true) {
		val raw = prompt("   Certificate file (PEM or DER)") ?: ""
		val file = File(raw.trim())
		if (file.exists() && file.isFile && file.canRead()) return file.absolutePath
		
		println("   ⚠️ File not found or not readable: ${file.absolutePath}")
	}
}

/**
 * Ask a yes/no question and return the boolean result.
 */
internal fun Terminal.confirm(text: String, default: Boolean = false): Boolean =
	YesNoPrompt(text, this, default = default).ask() ?: default

/**
 * Prompt for a secret value (password, PIN) with input hidden.
 * Returns `null` if the user cancels or submits blank input.
 */
internal fun Terminal.promptSecret(text: String): String? =
	prompt(text, hideInput = true)?.takeIf { it.isNotEmpty() }
