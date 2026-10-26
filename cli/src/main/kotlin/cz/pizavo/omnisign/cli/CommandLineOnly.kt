package cz.pizavo.omnisign.cli

import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.RawOption

/**
 * Take this option's value from the command line only, and drop a value Clikt read from a
 * variable, such as the one it infers from `autoEnvvarPrefix`.
 *
 * A secret option reads the environment through [resolveSecretOption] instead, which also looks
 * for the secret in a file and a systemd credential. Clikt hands on each value under the name it
 * found it by, which is one of the option's own names only on the command line.
 *
 * @return The option, whose value is `null` unless it was given on the command line.
 */
fun RawOption.commandLineOnly(): OptionWithValues<String?, String?, String> {
	val transformEachCall = transformEach
	return copy(
		transformValue = transformValue,
		transformEach = { values -> transformEachCall(this, values).takeIf { name in names } },
		transformAll = { calls -> calls.lastOrNull() },
		validator = {},
	)
}
