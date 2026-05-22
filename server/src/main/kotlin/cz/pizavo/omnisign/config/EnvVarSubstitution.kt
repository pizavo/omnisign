package cz.pizavo.omnisign.config

/**
 * Matches `${NAME}` placeholders where `NAME` is an uppercase/digit/underscore identifier.
 */
private val ENV_VAR_PATTERN = Regex("""\$\{([A-Z_][A-Z0-9_]*)}""")

/**
 * Replace every `${NAME}` placeholder in [text] with the value [resolve] returns for `NAME`
 * (the process environment by default).
 *
 * The substitution is textual and runs before parsing, so any scalar in a configuration
 * document can be sourced from the environment (including secrets, which therefore never
 * sit literally in the file). A referenced variable that resolves to `null` fails fast,
 * naming the variable.
 *
 * @param text Raw document text.
 * @param resolve Variable resolver; defaults to [System.getenv].
 * @return [text] with all placeholders expanded.
 * @throws IllegalStateException if a referenced variable is unset.
 */
fun substituteEnvVars(text: String, resolve: (String) -> String? = System::getenv): String =
	ENV_VAR_PATTERN.replace(text) { match ->
		val name = match.groupValues[1]
		resolve(name) ?: error(
			"Configuration references environment variable '$name' but it is not set. " +
				"Set $name before starting, or remove the reference.",
		)
	}
