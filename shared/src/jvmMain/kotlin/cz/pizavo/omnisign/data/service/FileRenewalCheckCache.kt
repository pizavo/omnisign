package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewalCheckCacheEntry
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * [RenewalCheckCache] backed by a JSON file (`path` → entry) in the OmniSign config directory.
 *
 * The map is loaded once into memory; entries for files that no longer exist are pruned on load, so
 * transient verification temp-files never accumulate. Reads and writes are best-effort: a missing or
 * unparseable file yields an empty cache, and a write failure is logged rather than propagated — so
 * a cache problem can never break a renewal run; at worst the next run re-validates.
 *
 * @param file Path to the JSON cache file.
 */
class FileRenewalCheckCache(
	private val file: Path,
) : RenewalCheckCache {

	private val entries: MutableMap<String, RenewalCheckCacheEntry> by lazy { loadEntries() }

	override fun get(path: String): RenewalCheckCacheEntry? = entries[path]

	override fun put(path: String, entry: RenewalCheckCacheEntry) {
		entries[path] = entry
		persist()
	}

	override fun remove(path: String) {
		if (entries.remove(path) != null) persist()
	}

	/**
	 * Load the persisted map, dropping entries whose file no longer exists so deleted documents and
	 * transient verification temp-files do not accumulate. Any failure yields an empty cache.
	 */
	private fun loadEntries(): MutableMap<String, RenewalCheckCacheEntry> {
		if (!file.exists()) return mutableMapOf()
		return try {
			JSON.decodeFromString(SERIALIZER, file.readText())
				.filterKeys { Path.of(it).exists() }
				.toMutableMap()
		} catch (e: Exception) {
			logger.warn(e) { "Could not read the renewal check cache at $file" }
			mutableMapOf()
		}
	}

	/**
	 * Write the in-memory map back to [file]. A failure is logged and otherwise ignored.
	 */
	private fun persist() {
		try {
			file.createParentDirectories()
			file.writeText(JSON.encodeToString(SERIALIZER, entries))
		} catch (e: Exception) {
			logger.warn(e) { "Could not write the renewal check cache at $file" }
		}
	}

	companion object {
		/** Serializer for the `path` → entry map. */
		private val SERIALIZER = MapSerializer(String.serializer(), RenewalCheckCacheEntry.serializer())

		/** JSON format for the cache file: pretty-printed and tolerant of unknown keys. */
		private val JSON = Json {
			prettyPrint = true
			ignoreUnknownKeys = true
			encodeDefaults = true
		}
	}
}
