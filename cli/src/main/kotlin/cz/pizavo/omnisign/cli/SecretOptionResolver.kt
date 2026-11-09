package cz.pizavo.omnisign.cli

import arrow.core.right
import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive
import cz.pizavo.omnisign.platform.PasswordCallback

/**
 * Resolve a secret option, such as `--keystore-password`, that the environment may set instead of
 * the command line.
 *
 * A value on the command line wins, and [PASSWORD_PROMPT_SENTINEL] there prompts through
 * [passwordCallback]. Without one, [secretReader] takes the secret from the variable [variable],
 * from the file `[variable]_FILE` names, or from the systemd credential [variable], and refuses it
 * when more than one of them is set or the file is not a safe place for it. A secret from the
 * environment is taken as it is, so `-` there is the secret rather than a request to prompt.
 *
 * @param commandLineValue The value given on the command line, or `null` when the option was not
 *   given there; see [commandLineOnly].
 * @param variable The variable the option names, such as `OMNISIGN_KEYSTORE_PASSWORD`.
 * @param environment Returns the value of a variable: the command context's
 *   [readEnvvar][com.github.ajalt.clikt.core.Context.readEnvvar], which tests replace.
 * @param secretReader Reads the secret from the environment.
 * @param passwordCallback Prompts for the secret when the command line asks for that.
 * @param prompt Message displayed when the secret is prompted for.
 * @return The secret, `null` when it is set nowhere or the prompt was cancelled, or the
 *   [cz.pizavo.omnisign.domain.model.error.ConfigurationError] that refused the one set in the
 *   environment.
 */
fun resolveSecretOption(
	commandLineValue: String?,
	variable: String,
	environment: (String) -> String?,
	secretReader: EnvironmentSecretReader,
	passwordCallback: PasswordCallback,
	prompt: String,
): OperationResult<Sensitive<String>?> =
	if (commandLineValue != null) {
		resolvePasswordOption(commandLineValue, passwordCallback, prompt)?.sensitive().right()
	} else {
		secretReader.read(variable, environment)
	}
