package cz.pizavo.omnisign.config

import arrow.core.getOrElse
import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.data.secret.SecretFileReader
import cz.pizavo.omnisign.data.secret.SecretSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode

/**
 * Matches `${NAME}` placeholders where `NAME` is an uppercase/digit/underscore identifier.
 */
private val ENV_VAR_PATTERN = Regex("""\$\{([A-Z_][A-Z0-9_]*)}""")

/**
 * The prefix of the names a placeholder may also take from a file or a systemd credential:
 * the server's own, so a variable another program reads, such as `SSL_CERT_FILE`, keeps its
 * meaning.
 */
private const val SECRET_PLACEHOLDER_PREFIX = "OMNISIGN_"

/**
 * Return the resolver the configuration loaders expand `${NAME}` placeholders with.
 *
 * A name that starts with `OMNISIGN_` is read the way [ServerSecrets] reads a secret: from the
 * variable `NAME`, from the file the variable `NAME_FILE` names, or from the systemd credential
 * `NAME`. Such a placeholder may stand for a secret, such as `auth.oauthStateSecret` or the TSA
 * password in `signing.yml`, so its value is held to the same rules: [secretReader] refuses a
 * value set in more than one of those places, and a file that is not a safe place for a secret.
 * A variable that is set but empty still expands to the empty string when no file or credential
 * sets the value instead. Any other name is read from its variable only.
 *
 * @param secretReader Reads an `OMNISIGN_` value from the environment.
 * @param environment Returns the value of a variable; the process environment by default.
 * @return A resolver that returns `null` for a name set nowhere, and throws
 *   [IllegalStateException] with the reason when [secretReader] refuses the value.
 */
fun placeholderResolver(
	secretReader: EnvironmentSecretReader = EnvironmentSecretReader(SecretFileReader()),
	environment: (String) -> String? = System::getenv,
): (String) -> String? = { name ->
	if (name.startsWith(SECRET_PLACEHOLDER_PREFIX)) {
		secretReader.read(name, environment).getOrElse { error(it.message) }?.value ?: environment(name)
	} else {
		environment(name)
	}
}

/**
 * Replace every `${NAME}` placeholder in [text] with the value [resolve] returns for `NAME`
 * (the process environment by default).
 *
 * A referenced variable that resolves to `null` fails fast, naming the variable, and for a name
 * [placeholderResolver] also reads from a file, the variable that names the file. Prefer
 * [substituteTreeValues] for config documents so that only parsed values - not comments or
 * keys - are expanded.
 *
 * @param text Raw text.
 * @param resolve Variable resolver; defaults to [System.getenv].
 * @return [text] with all placeholders expanded.
 * @throws IllegalStateException if a referenced variable is unset.
 */
fun substituteEnvVars(text: String, resolve: (String) -> String? = System::getenv): String =
	ENV_VAR_PATTERN.replace(text) { match ->
		val name = match.groupValues[1]
		val fileHint = if (name.startsWith(SECRET_PLACEHOLDER_PREFIX)) {
			", or set ${name}${SecretSource.FILE_SUFFIX} to the path of a file that holds the value,"
		} else {
			""
		}
		resolve(name) ?: error(
			"Configuration references environment variable '$name' but it is not set. " +
				"Set $name$fileHint before starting, or remove the reference.",
		)
	}

/**
 * Recursively expand `${NAME}` placeholders in every string **value** of the parsed JSON/YAML
 * tree [node], leaving object keys, numbers, and booleans untouched.
 *
 * Run this on a parsed tree (not the raw text) so that comments - already discarded by the
 * parser - are never substituted, and only real values reference the environment. A value
 * referencing an unset variable fails fast.
 *
 * @param node Root of the tree, mutated in place.
 * @param resolve Variable resolver; defaults to [System.getenv].
 * @throws IllegalStateException if a value references an unset variable.
 */
fun substituteTreeValues(node: JsonNode, resolve: (String) -> String? = System::getenv) {
	when (node) {
		is ObjectNode -> node.propertyNames().toList().forEach { name ->
			when (val child = node.get(name)) {
				is StringNode -> node.put(name, substituteEnvVars(child.asString(), resolve))
				is ObjectNode -> substituteTreeValues(child, resolve)
				is ArrayNode -> substituteTreeValues(child, resolve)
				else -> {}
			}
		}

		is ArrayNode -> for (i in 0 until node.size()) {
			when (val child = node.get(i)) {
				is StringNode -> node.set(i, StringNode.valueOf(substituteEnvVars(child.asString(), resolve)))
				is ObjectNode -> substituteTreeValues(child, resolve)
				is ArrayNode -> substituteTreeValues(child, resolve)
				else -> {}
			}
		}

		else -> {}
	}
}
