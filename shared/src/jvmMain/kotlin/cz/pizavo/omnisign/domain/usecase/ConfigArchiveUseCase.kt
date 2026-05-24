package cz.pizavo.omnisign.domain.usecase

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.archive.TrustArchiveEntry
import cz.pizavo.omnisign.data.archive.TrustArchiveManifest
import cz.pizavo.omnisign.domain.model.config.enums.ConfigFormat
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packs and unpacks configuration **export archives** — a ZIP that pairs the serialized
 * configuration with the directly-trusted certificates it depends on, which no longer live inside
 * the config text but in the [TrustStore].
 *
 * The archive layout is stable regardless of what it contains:
 * - `config.<ext>` — the exported app / global / profile in the chosen JSON / XML / YAML text.
 * - `trusted-certs/<fingerprint>.der` — one canonical DER per distinct referenced certificate.
 * - `trust-manifest.json` — the scope → `{fingerprint, type}` map, so an import can replay
 *   [TrustStore.add] per reference.
 *
 * An archive is always emitted, even when no certificate is referenced, so the outer container is a
 * stable contract. On import the certificate bytes are re-added through [TrustStore.add], whose
 * content-addressed dedup absorbs anything already present. Entries are sorted and given a fixed
 * timestamp so two exports of identical state are byte-for-byte identical.
 *
 * This wraps the platform-agnostic [ExportImportConfigUseCase] (config text only) with the
 * JVM-only ZIP and trust-store handling.
 *
 * @property configRepository Used to enumerate the profile scopes for a full-application export.
 * @property exportImport Serializes / deserializes the configuration text inside the archive.
 * @property trustStore Source and destination of the bundled trust material.
 */
class ConfigArchiveUseCase(
	private val configRepository: ConfigRepository,
	private val exportImport: ExportImportConfigUseCase,
	private val trustStore: TrustStore,
) : ConfigArchivePort {
	/**
	 * Build the full-configuration archive in the default JSON format ([ConfigArchivePort]).
	 */
	override suspend fun exportFullConfig(): OperationResult<ByteArray> = exportApp(ConfigFormat.JSON)

	/**
	 * Replace the entire configuration from a full-configuration [archive] ([ConfigArchivePort]).
	 */
	override suspend fun importFullConfig(archive: ByteArray): OperationResult<Unit> = importApp(archive)

	/**
	 * Export the full application configuration (global + every profile) and the trust material of
	 * all of their scopes as an archive.
	 */
	suspend fun exportApp(format: ConfigFormat): OperationResult<ByteArray> {
		val configText = exportImport.exportApp(format).getOrElse { return it.left() }
		val scopes = listOf(TrustScope.Global) +
			configRepository.getCurrentConfig().profiles.keys.map { TrustScope.Profile(it) }
		return buildArchive(format, configText, scopes)
	}

	/**
	 * Export the global configuration section and the global trust scope as an archive.
	 */
	suspend fun exportGlobal(format: ConfigFormat): OperationResult<ByteArray> {
		val configText = exportImport.exportGlobal(format).getOrElse { return it.left() }
		return buildArchive(format, configText, listOf(TrustScope.Global))
	}

	/**
	 * Export a single named profile and its own trust scope as an archive.
	 */
	suspend fun exportProfile(profileName: String, format: ConfigFormat): OperationResult<ByteArray> {
		val configText = exportImport.exportProfile(profileName, format).getOrElse { return it.left() }
		return buildArchive(format, configText, listOf(TrustScope.Profile(profileName)))
	}

	/**
	 * Import a full-application archive: replace the configuration, then restore every scope's
	 * trust references into the [TrustStore].
	 */
	suspend fun importApp(archive: ByteArray): OperationResult<Unit> {
		val entries = readZip(archive).getOrElse { return it.left() }
		val (configText, format) = readConfigEntry(entries).getOrElse { return it.left() }
		exportImport.importApp(configText, format).getOrElse { return it.left() }
		return restoreTrust(entries) { parseScope(it) }
	}

	/**
	 * Import a global archive: replace the global section, then restore the global trust scope.
	 */
	suspend fun importGlobal(archive: ByteArray): OperationResult<Unit> {
		val entries = readZip(archive).getOrElse { return it.left() }
		val (configText, format) = readConfigEntry(entries).getOrElse { return it.left() }
		exportImport.importGlobal(configText, format).getOrElse { return it.left() }
		return restoreTrust(entries) { parseScope(it) }
	}

	/**
	 * Import a profile archive: upsert the profile (optionally renamed via [overrideName]), then
	 * restore the archive's trust references into the imported profile's scope.
	 *
	 * @return The name the profile was saved under.
	 */
	suspend fun importProfile(archive: ByteArray, overrideName: String? = null): OperationResult<String> {
		val entries = readZip(archive).getOrElse { return it.left() }
		val (configText, format) = readConfigEntry(entries).getOrElse { return it.left() }
		val savedName = exportImport.importProfile(configText, format, overrideName).getOrElse { return it.left() }
		restoreTrust(entries) { TrustScope.Profile(savedName) }.getOrElse { return it.left() }
		return savedName.right()
	}

	/**
	 * Assemble the ZIP for [configText] plus the trust material referenced by [scopes].
	 *
	 * Per-scope membership and type come from [TrustStore.list]; the canonical DER for each
	 * referenced fingerprint is sourced from [TrustStore.resolve] (whose union covers every
	 * referenced certificate).
	 */
	private suspend fun buildArchive(
		format: ConfigFormat,
		configText: String,
		scopes: List<TrustScope>,
	): OperationResult<ByteArray> {
		val manifestEntries = mutableListOf<TrustArchiveEntry>()
		val derByFingerprint = mutableMapOf<String, ByteArray>()
		for (scope in scopes) {
			trustStore.list(scope).getOrElse { return it.left() }
				.forEach { manifestEntries += TrustArchiveEntry(scopeKey(scope), it.fingerprint, it.type) }
			trustStore.resolve(scope).getOrElse { return it.left() }
				.forEach { derByFingerprint.putIfAbsent(it.fingerprint, it.der) }
		}

		val files = sortedMapOf<String, ByteArray>()
		files["config.${format.extension}"] = configText.encodeToByteArray()
		val sortedEntries = manifestEntries.sortedWith(compareBy({ it.scope }, { it.fingerprint }))
		files[MANIFEST_ENTRY] = json.encodeToString(
			TrustArchiveManifest.serializer(),
			TrustArchiveManifest(entries = sortedEntries),
		).encodeToByteArray()
		for (fingerprint in sortedEntries.map { it.fingerprint }.toSortedSet()) {
			val der = derByFingerprint[fingerprint] ?: return ConfigurationError.InvalidConfiguration(
				message = "Trust store is missing the bytes for referenced certificate $fingerprint",
			).left()
			files["$TRUSTED_CERTS_DIR/$fingerprint.der"] = der
		}
		return writeZip(files).right()
	}

	/**
	 * Re-add every certificate the manifest references into the scope [scopeOf] maps its entry to.
	 */
	private suspend fun restoreTrust(
		entries: Map<String, ByteArray>,
		scopeOf: (String) -> TrustScope,
	): OperationResult<Unit> {
		val manifest = readManifest(entries).getOrElse { return it.left() }
		for (entry in manifest.entries) {
			val der = entries["$TRUSTED_CERTS_DIR/${entry.fingerprint}.der"]
				?: return ConfigurationError.InvalidConfiguration(
					message = "Archive references certificate ${entry.fingerprint} but its DER entry is missing",
				).left()
			trustStore.add(scopeOf(entry.scope), der, entry.type, source = ARCHIVE_SOURCE)
				.getOrElse { return it.left() }
		}
		return Unit.right()
	}

	/**
	 * Locate the single `config.<ext>` entry and resolve its [ConfigFormat] from the extension.
	 */
	private fun readConfigEntry(entries: Map<String, ByteArray>): OperationResult<Pair<String, ConfigFormat>> {
		val configEntry = entries.entries.firstOrNull { it.key.substringAfterLast('/').startsWith("config.") }
			?: return ConfigurationError.InvalidConfiguration(
				message = "Configuration archive is missing a config.* entry",
			).left()
		val format = ConfigFormat.fromExtension(configEntry.key.substringAfterLast('.'))
			?: return ConfigurationError.InvalidConfiguration(
				message = "Configuration archive has an unrecognized config format: ${configEntry.key}",
			).left()
		return (configEntry.value.decodeToString() to format).right()
	}

	/**
	 * Parse [MANIFEST_ENTRY] from [entries], treating its absence as an empty manifest.
	 */
	private fun readManifest(entries: Map<String, ByteArray>): OperationResult<TrustArchiveManifest> {
		val bytes = entries[MANIFEST_ENTRY] ?: return TrustArchiveManifest().right()
		return try {
			json.decodeFromString(TrustArchiveManifest.serializer(), bytes.decodeToString()).right()
		} catch (e: SerializationException) {
			ConfigurationError.InvalidConfiguration(
				message = "Configuration archive has a corrupt trust manifest",
				details = e.message,
			).left()
		}
	}

	/**
	 * Read every non-directory ZIP entry into a name → bytes map.
	 */
	private fun readZip(archive: ByteArray): OperationResult<Map<String, ByteArray>> = try {
		val result = LinkedHashMap<String, ByteArray>()
		ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
			var entry = zip.nextEntry
			while (entry != null) {
				if (!entry.isDirectory) result[entry.name] = zip.readBytes()
				entry = zip.nextEntry
			}
		}
		result.right()
	} catch (e: IOException) {
		ConfigurationError.InvalidConfiguration(
			message = "Could not read the configuration archive",
			details = e.message,
		).left()
	}

	/**
	 * Write [files] (already sorted by name) into a deterministic ZIP: fixed entry timestamps and a
	 * stable order, so identical state produces byte-identical archives.
	 */
	private fun writeZip(files: Map<String, ByteArray>): ByteArray {
		val buffer = ByteArrayOutputStream()
		ZipOutputStream(buffer).use { zip ->
			for ((name, bytes) in files) {
				zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ENTRY_TIME })
				zip.write(bytes)
				zip.closeEntry()
			}
		}
		return buffer.toByteArray()
	}

	/**
	 * Encode a [TrustScope] as a manifest scope key.
	 */
	private fun scopeKey(scope: TrustScope): String = when (scope) {
		is TrustScope.Global -> GLOBAL_SCOPE
		is TrustScope.Profile -> "$PROFILE_SCOPE_PREFIX${scope.name}"
	}

	/**
	 * Decode a manifest scope key back into a [TrustScope].
	 */
	private fun parseScope(key: String): TrustScope =
		if (key == GLOBAL_SCOPE) TrustScope.Global
		else TrustScope.Profile(key.removePrefix(PROFILE_SCOPE_PREFIX))

	companion object {
		/**
		 * Magic-byte sniff for the local-file-header of a ZIP (`PK`), so a caller can
		 * route a legacy plain-text export to the text importer instead of this archive importer.
		 */
		fun isArchive(bytes: ByteArray): Boolean =
			bytes.size >= 4 &&
				bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
				bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

		/** Archive entry holding the scope → `{fingerprint, type}` manifest. */
		private const val MANIFEST_ENTRY = "trust-manifest.json"

		/** Archive directory holding the content-addressed DER files. */
		private const val TRUSTED_CERTS_DIR = "trusted-certs"

		/** Manifest scope key for the global scope. */
		private const val GLOBAL_SCOPE = "global"

		/** Manifest scope key prefix for a profile scope. */
		private const val PROFILE_SCOPE_PREFIX = "profile:"

		/** Provenance recorded in the trust-store index for certificates restored from an archive. */
		private const val ARCHIVE_SOURCE = "archive"

		/** Fixed ZIP entry timestamp (2000-01-01 UTC) for reproducible archives. */
		private const val FIXED_ENTRY_TIME = 946684800000L

		/** Pretty-printed JSON for the human-inspectable manifest. */
		private val json = Json { prettyPrint = true }
	}
}
