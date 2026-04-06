package cz.pizavo.omnisign.cli

import cz.pizavo.omnisign.platform.PasswordCallback

/**
 * Sentinel value that, when passed as the value of `--timestamp-password`, causes the CLI
 * to prompt interactively with hidden input instead of accepting the password on the
 * command line (where it would be visible in process listings).
 *
 * Usage: `omnisign sign --timestamp-password -`
 */
const val PASSWORD_PROMPT_SENTINEL = "-"

/**
 * Resolve a raw `--timestamp-password` CLI value into the actual password string.
 *
 * - `null` → the option was not supplied; returns `null`.
 * - [PASSWORD_PROMPT_SENTINEL] (`"-"`) → prompts the user interactively via [passwordCallback]
 *   with echo suppressed, avoiding process-list exposure.
 * - Any other value → returned as-is (backwards-compatible, but visible in `ps`/`wmic`).
 *
 * @param rawValue The raw string from the `--timestamp-password` Clikt option.
 * @param passwordCallback Platform callback used for the interactive prompt.
 * @param prompt Message displayed when interactive input is requested.
 * @return The resolved password, or `null` when the option was absent or the user cancelled.
 */
fun resolvePasswordOption(
	rawValue: String?,
	passwordCallback: PasswordCallback,
	prompt: String = "Timestamp server password",
): String? {
	if (rawValue == null) return null
	if (rawValue == PASSWORD_PROMPT_SENTINEL) {
		return passwordCallback.requestPassword(prompt, "Timestamp Password")
	}
	return rawValue
}

