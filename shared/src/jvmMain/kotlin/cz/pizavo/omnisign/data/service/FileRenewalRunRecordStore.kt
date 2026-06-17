package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * [RenewalRunRecordStore] backed by a small JSON file in the OmniSign config directory.
 *
 * Reads and writes are best-effort: a missing or unparseable file yields `null` from [load]
 * (treated as "no history") and a write failure is logged rather than propagated, so status
 * bookkeeping can never break a renewal run.
 *
 * @param file Path to the JSON record file.
 */
class FileRenewalRunRecordStore(
	private val file: Path,
) : RenewalRunRecordStore {

	override fun load(): RenewalRunRecord? {
		if (!file.exists()) return null
		return try {
			JSON.decodeFromString(RenewalRunRecord.serializer(), file.readText())
		} catch (e: Exception) {
			logger.warn(e) { "Could not read the renewal run record at $file" }
			null
		}
	}

	override fun save(record: RenewalRunRecord) {
		try {
			file.createParentDirectories()
			file.writeText(JSON.encodeToString(RenewalRunRecord.serializer(), record))
		} catch (e: Exception) {
			logger.warn(e) { "Could not write the renewal run record at $file" }
		}
	}

	companion object {
		/** JSON format for the record file: pretty-printed and tolerant of unknown keys. */
		private val JSON = Json {
			prettyPrint = true
			ignoreUnknownKeys = true
			encodeDefaults = true
		}
	}
}
