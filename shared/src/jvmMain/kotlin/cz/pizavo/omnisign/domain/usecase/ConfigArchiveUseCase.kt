package cz.pizavo.omnisign.domain.usecase

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.archive.TrustArchiveEntry
import cz.pizavo.omnisign.data.archive.TrustArchiveManifest
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
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
import java.time.LocalDateTime
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
 * timestamp, so two exports of identical state that the same JDK writes are byte-for-byte identical,
 * whatever the machine's time zone.
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
	 *
	 * The manifest lists the scope's certificates under the name the exported profile carries, which
	 * [importProfile] finds them by, even when the configuration keeps the profile under another key.
	 */
	suspend fun exportProfile(profileName: String, format: ConfigFormat): OperationResult<ByteArray> {
		val configText = exportImport.exportProfile(profileName, format).getOrElse { return it.left() }
		val ownName = configRepository.getCurrentConfig().profiles[profileName]?.name ?: profileName
		return buildArchive(format, configText, listOf(TrustScope.Profile(profileName))) {
			TrustScope.Profile(ownName)
		}
	}

	/**
	 * Import a full-application archive: replace the configuration, then restore every scope's
	 * trust references into the [TrustStore].
	 *
	 * The archive is checked before anything is changed. One that holds the global settings or a
	 * profile instead of a full configuration, or no configuration, is refused, because
	 * [ExportImportConfigUseCase.importApp] refuses its configuration, and so is one whose manifest
	 * or a certificate it lists cannot be read, as [readTrust] checks first. Only a failure to store
	 * a certificate can leave the import half done, with the configuration already saved.
	 */
	suspend fun importApp(archive: ByteArray): OperationResult<Unit> {
		val entries = readZip(archive).getOrElse { return it.left() }
		val (configText, format) = readConfigEntry(entries).getOrElse { return it.left() }
		val certificates = readTrust(entries) { true }.getOrElse { return it.left() }
		exportImport.importApp(configText, format).getOrElse { return it.left() }
		return restoreTrust(certificates) { parseScope(it) }
	}

	/**
	 * Import a global archive: replace the global section, then restore the global trust scope.
	 *
	 * Only the manifest's global entries are restored; a profile's certificates belong to an import
	 * of that profile. The archive is checked before anything is changed, as [importApp] describes,
	 * and one that holds a full configuration or a profile instead of the global settings is refused.
	 */
	suspend fun importGlobal(archive: ByteArray): OperationResult<Unit> {
		val entries = readZip(archive).getOrElse { return it.left() }
		val (configText, format) = readConfigEntry(entries).getOrElse { return it.left() }
		val certificates = readTrust(entries) { it == GLOBAL_SCOPE }.getOrElse { return it.left() }
		exportImport.importGlobal(configText, format).getOrElse { return it.left() }
		return restoreTrust(certificates) { TrustScope.Global }
	}

	/**
	 * Import a profile archive: upsert the profile (optionally renamed via [overrideName]), then
	 * restore the archive's trust references into the imported profile's scope.
	 *
	 * Only the manifest's entries for the profile the archive's configuration holds are restored,
	 * found by the name that configuration gives it, not by [overrideName]; the global certificates
	 * belong to an import of the global settings, and another profile's to an import of that
	 * profile. The archive is checked before anything is changed, as [importApp] describes, and one
	 * that holds a full configuration or the global settings instead of a profile is refused.
	 *
	 * @return The name the profile was saved under.
	 */
	suspend fun importProfile(archive: ByteArray, overrideName: String? = null): OperationResult<String> {
		val entries = readZip(archive).getOrElse { return it.left() }
		val (configText, format) = readConfigEntry(entries).getOrElse { return it.left() }
		val profile = exportImport.readProfile(configText, format).getOrElse { return it.left() }
		val ownScope = scopeKey(TrustScope.Profile(profile.name))
		val certificates = readTrust(entries) { it == ownScope }.getOrElse { return it.left() }
		val savedName = exportImport.saveProfile(profile, overrideName).getOrElse { return it.left() }
		restoreTrust(certificates) { TrustScope.Profile(savedName) }.getOrElse { return it.left() }
		return savedName.right()
	}

	/**
	 * Assemble the ZIP for [configText] plus the trust material referenced by [scopes].
	 *
	 * Per-scope membership and type come from [TrustStore.list]; the canonical DER for each
	 * referenced fingerprint is sourced from [TrustStore.resolve] (whose union covers every
	 * referenced certificate). The manifest lists each scope's certificates under the scope
	 * [listedAs] gives for it, the scope itself unless an export says otherwise.
	 */
	private suspend fun buildArchive(
		format: ConfigFormat,
		configText: String,
		scopes: List<TrustScope>,
		listedAs: (TrustScope) -> TrustScope = { it },
	): OperationResult<ByteArray> {
		val manifestEntries = mutableListOf<TrustArchiveEntry>()
		val derByFingerprint = mutableMapOf<String, ByteArray>()
		for (scope in scopes) {
			trustStore.list(scope).getOrElse { return it.left() }
				.forEach { manifestEntries += TrustArchiveEntry(scopeKey(listedAs(scope)), it.fingerprint, it.type) }
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
			val der = derByFingerprint[fingerprint]
				?: return ConfigurationError.missingCertificateBytes(fingerprint).left()
			files["$TRUSTED_CERTS_DIR/$fingerprint.der"] = der
		}
		return writeZip(files).right()
	}

	/**
	 * The certificates the manifest lists under the scope keys [accept] takes, each checked to be in
	 * the archive and to parse, so that an archive whose trust material is broken is refused before an
	 * import changes anything.
	 */
	private suspend fun readTrust(
		entries: Map<String, ByteArray>,
		accept: (String) -> Boolean,
	): OperationResult<List<ArchivedCertificate>> {
		val manifest = readManifest(entries).getOrElse { return it.left() }
		return manifest.entries.filter { accept(it.scope) }.map { entry ->
			val der = entries["$TRUSTED_CERTS_DIR/${entry.fingerprint}.der"]
				?: return ConfigurationError.missingDerEntry(entry.fingerprint).left()
			trustStore.inspect(der).getOrElse { return it.left() }
			ArchivedCertificate(entry.scope, der, entry.type)
		}.right()
	}

	/**
	 * Re-add [certificates] to the [TrustStore], each into the scope [scopeOf] maps its scope key to.
	 */
	private suspend fun restoreTrust(
		certificates: List<ArchivedCertificate>,
		scopeOf: (String) -> TrustScope,
	): OperationResult<Unit> {
		for (certificate in certificates) {
			trustStore.add(scopeOf(certificate.scopeKey), certificate.der, certificate.type, source = ARCHIVE_SOURCE)
				.getOrElse { return it.left() }
		}
		return Unit.right()
	}

	/**
	 * Locate the single `config.<ext>` entry and resolve its [ConfigFormat] from the extension.
	 */
	private fun readConfigEntry(entries: Map<String, ByteArray>): OperationResult<Pair<String, ConfigFormat>> {
		val configEntry = entries.entries.firstOrNull { it.key.substringAfterLast('/').startsWith("config.") }
			?: return ConfigurationError.archiveMissingConfigEntry().left()
		val format = ConfigFormat.fromExtension(configEntry.key.substringAfterLast('.'))
			?: return ConfigurationError.archiveUnrecognizedFormat(configEntry.key).left()
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
			ConfigurationError.archiveCorruptManifest(details = e.message).left()
		}
	}

	/**
	 * Read every non-directory ZIP entry into a name → bytes map.
	 *
	 * An archive that unpacks to more than [MAX_UNPACKED_MIB] MiB is refused as soon as it does, so
	 * that a small archive that inflates without end cannot exhaust the memory. So is an archive with
	 * an entry name that is not valid UTF-8, which the ZIP reader reports as a
	 * [java.util.zip.ZipException], an [IOException].
	 */
	private fun readZip(archive: ByteArray): OperationResult<Map<String, ByteArray>> {
		val result = LinkedHashMap<String, ByteArray>()
		var unpacked = 0
		try {
			ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
				var entry = zip.nextEntry
				while (entry != null) {
					if (!entry.isDirectory) {
						val bytes = zip.readNBytes(MAX_UNPACKED_BYTES - unpacked + 1)
						unpacked += bytes.size
						if (unpacked > MAX_UNPACKED_BYTES) {
							return ConfigurationError.archiveUnreadable(
								details = "The archive unpacks to more than $MAX_UNPACKED_MIB MiB",
							).left()
						}
						result[entry.name] = bytes
					}
					entry = zip.nextEntry
				}
			}
		} catch (e: IOException) {
			return ConfigurationError.archiveUnreadable(details = e.message).left()
		}
		return result.right()
	}

	/**
	 * Write [files] (already sorted by name) into a deterministic ZIP: fixed entry timestamps and a
	 * stable order, so identical state produces byte-identical archives whatever the time zone of the
	 * machine that writes them.
	 */
	private fun writeZip(files: Map<String, ByteArray>): ByteArray {
		val buffer = ByteArrayOutputStream()
		ZipOutputStream(buffer).use { zip ->
			for ((name, bytes) in files) {
				zip.putNextEntry(ZipEntry(name).apply { timeLocal = FIXED_ENTRY_TIME })
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

	/**
	 * A certificate an archive's manifest lists, read from the archive and checked to parse.
	 *
	 * @property scopeKey The manifest scope key the certificate was exported under.
	 * @property der The certificate's DER bytes.
	 * @property type The trust role the certificate had in that scope.
	 */
	private class ArchivedCertificate(val scopeKey: String, val der: ByteArray, val type: TrustedCertificateType)

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

		/**
		 * Fixed ZIP entry timestamp for reproducible archives: 2000-01-01 00:00, written as an MS-DOS
		 * date and time, which carry no time zone, so that the bytes do not depend on the machine's
		 * time zone.
		 */
		private val FIXED_ENTRY_TIME: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)

		/** How many mebibytes an archive may unpack to before an import refuses it. */
		private const val MAX_UNPACKED_MIB = 64

		/** How many bytes an archive may unpack to before an import refuses it. */
		private const val MAX_UNPACKED_BYTES = MAX_UNPACKED_MIB * 1024 * 1024

		/** Pretty-printed JSON for the human-inspectable manifest. */
		private val json = Json { prettyPrint = true }
	}
}
