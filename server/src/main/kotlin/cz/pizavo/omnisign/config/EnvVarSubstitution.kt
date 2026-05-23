package cz.pizavo.omnisign.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Matches `${NAME}` placeholders where `NAME` is an uppercase/digit/underscore identifier.
 */
private val ENV_VAR_PATTERN = Regex("""\$\{([A-Z_][A-Z0-9_]*)}""")

/**
 * Replace every `${NAME}` placeholder in [text] with the value [resolve] returns for `NAME`
 * (the process environment by default).
 *
 * A referenced variable that resolves to `null` fails fast, naming the variable. Prefer
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
		resolve(name) ?: error(
			"Configuration references environment variable '$name' but it is not set. " +
				"Set $name before starting, or remove the reference.",
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
		is ObjectNode -> node.fieldNames().asSequence().toList().forEach { name ->
			when (val child = node.get(name)) {
				is TextNode -> node.put(name, substituteEnvVars(child.asText(), resolve))
				is ObjectNode -> substituteTreeValues(child, resolve)
				is ArrayNode -> substituteTreeValues(child, resolve)
				else -> {}
			}
		}

		is ArrayNode -> for (i in 0 until node.size()) {
			when (val child = node.get(i)) {
				is TextNode -> node.set(i, TextNode.valueOf(substituteEnvVars(child.asText(), resolve)))
				is ObjectNode -> substituteTreeValues(child, resolve)
				is ArrayNode -> substituteTreeValues(child, resolve)
				else -> {}
			}
		}

		else -> {}
	}
}
