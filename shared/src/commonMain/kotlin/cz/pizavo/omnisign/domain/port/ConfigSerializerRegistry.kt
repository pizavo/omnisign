package cz.pizavo.omnisign.domain.port

import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat

/**
 * Registry that holds one [ConfigSerializer] per [ConfigFormat] and dispatches
 * serialization requests to the appropriate implementation.
 *
 * @param serializers All available serializer implementations.
 */
class ConfigSerializerRegistry(serializers: List<ConfigSerializer>) {
	private val map: Map<ConfigFormat, ConfigSerializer> =
		serializers.associateBy { it.format }
	
	/**
	 * Retrieve the serializer for the given [format].
	 *
	 * @param format The desired configuration format.
	 * @return The matching [ConfigSerializer], or null if none is registered.
	 */
	fun get(format: ConfigFormat): ConfigSerializer? = map[format]
	
	/**
	 * Return all registered formats.
	 */
	val supportedFormats: Set<ConfigFormat> get() = map.keys
}

