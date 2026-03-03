package cz.pizavo.omnisign.domain.model.config.enums

/**
 * Supported formats for configuration export and import.
 */
enum class ConfigFormat(
	/**
	 * Conventional file extension for this format (without leading dot).
	 */
	val extension: String
) {
	JSON("json"),
	XML("xml"),
	YAML("yaml");
	
	companion object {
		/**
		 * Resolve a [ConfigFormat] from a file extension or format name string.
		 * The lookup is case-insensitive. Returns null when no match is found.
		 *
		 * @param value Extension or name to look up (e.g. "yml", "xml", "JSON").
		 */
		fun fromExtension(value: String): ConfigFormat? {
			val normalized = value.lowercase().trimStart('.')
			return when (normalized) {
				"json" -> JSON
				"xml" -> XML
				"yaml", "yml" -> YAML
				else -> null
			}
		}
	}
}

