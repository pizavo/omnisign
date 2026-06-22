package cz.pizavo.omnisign.data.trust

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Filesystem [TrustStore] backed by a content-addressed directory and a CBOR index.
 *
 * Layout under [trustDir]:
 * - `contents.cbor` - the [TrustStoreIndex] (scopes, references, cached metadata).
 * - `sha256-<hex>.der` - one canonical-DER file per distinct certificate.
 *
 * Identity is the certificate's SHA-256 fingerprint, so dedup is structural (a file either exists
 * or it does not) and a file can be verified against its own name. The index is written atomically
 * (temp + rename); the directory and files are restricted to the owner on POSIX systems.
 *
 * Thread-safe: every operation is serialized by a [Mutex], and the parsed index is cached in
 * memory between operations.
 *
 * @property trustDir The app-managed trust directory.
 */
@OptIn(ExperimentalSerializationApi::class)
class FileTrustStore(
	private val trustDir: Path = defaultTrustDir(),
) : TrustStore {

	private val cbor = Cbor { ignoreUnknownKeys = true }
	private val mutex = Mutex()

	@Volatile
	private var cachedIndex: TrustStoreIndex? = null

	override suspend fun add(
		scope: TrustScope,
		certBytes: ByteArray,
		type: TrustedCertificateType,
		source: String?,
	): OperationResult<TrustedCertificate> = mutex.withLock {
		val x509 = runCatching { parse(certBytes) }.getOrElse {
			return@withLock TrustStoreError.parseFailed(
				details = it.message,
				cause = it,
			).left()
		}
		try {
			val index = ensureIndex()
			val der = x509.encoded
			val fingerprint = certFingerprint(der)
			val file = certFile(fingerprint)
			if (!file.exists()) writeFile(file, der)

			val existing = index.certs[fingerprint]
			val entry = when {
				existing == null -> CertEntry(
					subjectDN = x509.subjectX500Principal.name,
					notBefore = x509.notBefore.time,
					notAfter = x509.notAfter.time,
					sources = listOfNotNull(source),
				)

				source != null && source !in existing.sources ->
					existing.copy(sources = existing.sources + source)

				else -> existing
			}
			val key = scope.key()
			val refs = index.scopes[key].orEmpty().filterNot { it.fingerprint == fingerprint } +
				CertRef(fingerprint, type)
			persist(
				index.copy(
					certs = index.certs + (fingerprint to entry),
					scopes = index.scopes + (key to refs),
				)
			)
			toReadModel(fingerprint, entry, type).right()
		} catch (e: Exception) {
			TrustStoreError.storageFailed(
				details = e.message,
				cause = e,
			).left()
		}
	}

	override suspend fun remove(scope: TrustScope, fingerprint: String): OperationResult<Unit> =
		mutex.withLock {
			try {
				val index = ensureIndex()
				val key = scope.key()
				val refs = index.scopes[key].orEmpty()
				if (refs.none { it.fingerprint == fingerprint }) {
					return@withLock notFound(fingerprint, scope)
				}
				val remaining = refs.filterNot { it.fingerprint == fingerprint }
				val scopes =
					if (remaining.isEmpty()) index.scopes - key else index.scopes + (key to remaining)
				persist(gc(index.copy(scopes = scopes)))
				Unit.right()
			} catch (e: Exception) {
				fail(e)
			}
		}

	override suspend fun list(scope: TrustScope): OperationResult<List<TrustedCertificate>> =
		mutex.withLock {
			try {
				val index = ensureIndex()
				index.scopes[scope.key()].orEmpty().mapNotNull { ref ->
					index.certs[ref.fingerprint]?.let { toReadModel(ref.fingerprint, it, ref.type) }
				}.right()
			} catch (e: Exception) {
				fail(e)
			}
		}

	override suspend fun inspect(certBytes: ByteArray): OperationResult<TrustedCertificate> {
		val x509 = runCatching { parse(certBytes) }.getOrElse {
			return TrustStoreError.parseFailed(
				details = it.message,
				cause = it,
			).left()
		}
		val der = x509.encoded
		return TrustedCertificate(
			fingerprint = certFingerprint(der),
			subjectDN = x509.subjectX500Principal.name,
			notBefore = Instant.fromEpochMilliseconds(x509.notBefore.time),
			notAfter = Instant.fromEpochMilliseconds(x509.notAfter.time),
			type = TrustedCertificateType.ANY,
		).right()
	}

	override suspend fun setType(
		scope: TrustScope,
		fingerprint: String,
		type: TrustedCertificateType,
	): OperationResult<Unit> = mutex.withLock {
		try {
			val index = ensureIndex()
			val key = scope.key()
			val refs = index.scopes[key].orEmpty()
			if (refs.none { it.fingerprint == fingerprint }) {
				return@withLock notFound(fingerprint, scope)
			}
			val updated = refs.map { if (it.fingerprint == fingerprint) it.copy(type = type) else it }
			persist(index.copy(scopes = index.scopes + (key to updated)))
			Unit.right()
		} catch (e: Exception) {
			fail(e)
		}
	}

	override suspend fun clearProfileScope(profileName: String): OperationResult<Unit> =
		mutex.withLock {
			try {
				val index = ensureIndex()
				val key = TrustScope.Profile(profileName).key()
				if (key !in index.scopes) return@withLock Unit.right()
				persist(gc(index.copy(scopes = index.scopes - key)))
				Unit.right()
			} catch (e: Exception) {
				fail(e)
			}
		}

	override suspend fun resolve(scope: TrustScope): OperationResult<List<ResolvedTrustAnchor>> =
		mutex.withLock {
			try {
				val index = ensureIndex()
				val merged = LinkedHashMap<String, TrustedCertificateType>()
				index.scopes[GLOBAL_KEY].orEmpty().forEach { merged[it.fingerprint] = it.type }
				if (scope != TrustScope.Global) {
					index.scopes[scope.key()].orEmpty().forEach { merged[it.fingerprint] = it.type }
				}
				merged.mapNotNull { (fingerprint, type) ->
					val file = certFile(fingerprint)
					if (file.exists()) {
						ResolvedTrustAnchor(fingerprint, type, file.readBytes())
					} else {
						logger.warn { "Trust file for $fingerprint is missing; skipping it" }
						null
					}
				}.right()
			} catch (e: Exception) {
				fail(e)
			}
		}

	override suspend fun reference(
		scope: TrustScope,
		fingerprint: String,
		type: TrustedCertificateType,
	): OperationResult<Unit> = mutex.withLock {
		try {
			val index = ensureIndex()
			if (fingerprint !in index.certs || !certFile(fingerprint).exists()) {
				return@withLock TrustStoreError.noStoredCertificate(fingerprint, scope).left()
			}
			val key = scope.key()
			val refs = index.scopes[key].orEmpty().filterNot { it.fingerprint == fingerprint } +
				CertRef(fingerprint, type)
			persist(index.copy(scopes = index.scopes + (key to refs)))
			Unit.right()
		} catch (e: Exception) {
			fail(e)
		}
	}

	override suspend fun findBySource(source: String): OperationResult<String?> = mutex.withLock {
		try {
			ensureIndex().certs.entries.firstOrNull { source in it.value.sources }?.key.right()
		} catch (e: Exception) {
			fail(e)
		}
	}

	override suspend fun scopes(): OperationResult<Set<TrustScope>> = mutex.withLock {
		try {
			ensureIndex().scopes.keys.mapTo(mutableSetOf()) { key ->
				if (key == GLOBAL_KEY) TrustScope.Global else TrustScope.Profile(key.removePrefix("profile:"))
			}.right()
		} catch (e: Exception) {
			fail(e)
		}
	}

	private fun parse(bytes: ByteArray): X509Certificate =
		bytes.inputStream().use {
			CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
		}

	private fun certFile(fingerprint: String): Path = trustDir.resolve("$fingerprint.der")

	private fun TrustScope.key(): String = when (this) {
		TrustScope.Global -> GLOBAL_KEY
		is TrustScope.Profile -> "profile:$name"
	}

	private fun notFound(fingerprint: String, scope: TrustScope): Either<TrustStoreError, Nothing> =
		TrustStoreError.notFoundInScope(fingerprint, scope).left()

	private fun fail(e: Exception): Either<TrustStoreError, Nothing> =
		TrustStoreError.operationFailed(details = e.message, cause = e).left()

	private fun toReadModel(
		fingerprint: String,
		entry: CertEntry,
		type: TrustedCertificateType,
	): TrustedCertificate = TrustedCertificate(
		fingerprint = fingerprint,
		subjectDN = entry.subjectDN,
		notBefore = Instant.fromEpochMilliseconds(entry.notBefore),
		notAfter = Instant.fromEpochMilliseconds(entry.notAfter),
		type = type,
	)

	private fun ensureIndex(): TrustStoreIndex {
		cachedIndex?.let { return it }
		val repaired = repair(loadIndex())
		cachedIndex = repaired
		return repaired
	}

	private fun loadIndex(): TrustStoreIndex {
		val file = trustDir.resolve(INDEX_FILE)
		if (!file.exists()) return TrustStoreIndex()
		return runCatching {
			cbor.decodeFromByteArray(TrustStoreIndex.serializer(), file.readBytes())
		}.getOrElse {
			logger.warn(it) { "Could not read trust index $file; starting from an empty index" }
			TrustStoreIndex()
		}
	}

	/**
	 * Reconcile the loaded [index] with the filesystem: drop references to files that are missing
	 * or that fail their own checksum, and delete orphan files no longer in the index.
	 */
	private fun repair(index: TrustStoreIndex): TrustStoreIndex {
		if (!trustDir.exists()) return index
		val validCerts = index.certs.filterKeys { fingerprint ->
			val file = certFile(fingerprint)
			when {
				!file.exists() -> {
					logger.warn { "Trust index references missing file $fingerprint; dropping it" }
					false
				}

				runCatching { certFingerprint(file.readBytes()) }.getOrNull() != fingerprint -> {
					logger.warn { "Trust file $fingerprint fails its own checksum; dropping it" }
					false
				}

				else -> true
			}
		}
		val validScopes = index.scopes
			.mapValues { (_, refs) -> refs.filter { it.fingerprint in validCerts } }
			.filterValues { it.isNotEmpty() }
		deleteOrphanFiles(validCerts.keys)
		val repaired = index.copy(certs = validCerts, scopes = validScopes)
		if (repaired != index) writeIndex(repaired)
		return repaired
	}

	private fun deleteOrphanFiles(known: Set<String>) {
		runCatching {
			trustDir.listDirectoryEntries("*.der").forEach { file ->
				val fingerprint = file.name.removeSuffix(".der")
				if (fingerprint !in known) {
					logger.warn { "Deleting orphan trust file ${file.name}" }
					Files.deleteIfExists(file)
				}
			}
		}.onFailure { logger.warn(it) { "Failed to scan for orphan trust files" } }
	}

	/**
	 * Remove certificate entries (and their files) no longer referenced by any scope.
	 */
	private fun gc(index: TrustStoreIndex): TrustStoreIndex {
		val referenced = index.scopes.values.flatten().mapTo(mutableSetOf()) { it.fingerprint }
		val orphans = index.certs.keys - referenced
		if (orphans.isEmpty()) return index
		orphans.forEach { fingerprint ->
			runCatching { Files.deleteIfExists(certFile(fingerprint)) }
				.onFailure { logger.warn(it) { "Failed to delete unreferenced trust file $fingerprint" } }
		}
		return index.copy(certs = index.certs - orphans)
	}

	private fun persist(index: TrustStoreIndex) {
		writeIndex(index)
		cachedIndex = index
	}

	private fun writeIndex(index: TrustStoreIndex) {
		Files.createDirectories(trustDir)
		restrictDirPermissions(trustDir)
		val tmp = trustDir.resolve("$INDEX_FILE.tmp")
		Files.write(tmp, cbor.encodeToByteArray(TrustStoreIndex.serializer(), index))
		restrictFilePermissions(tmp)
		val target = trustDir.resolve(INDEX_FILE)
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun writeFile(file: Path, der: ByteArray) {
		Files.createDirectories(trustDir)
		restrictDirPermissions(trustDir)
		Files.write(file, der)
		restrictFilePermissions(file)
	}

	private fun restrictFilePermissions(path: Path) {
		try {
			Files.setPosixFilePermissions(
				path,
				setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
			)
		} catch (_: UnsupportedOperationException) {
		}
	}

	private fun restrictDirPermissions(path: Path) {
		try {
			Files.setPosixFilePermissions(
				path,
				setOf(
					PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE,
				),
			)
		} catch (_: UnsupportedOperationException) {
		}
	}

	companion object {
		private const val INDEX_FILE = "contents.cbor"
		private const val GLOBAL_KEY = "global"

		/**
		 * The default trust directory: `trusted-certs/` beside the app config file.
		 */
		fun defaultTrustDir(): Path =
			FileConfigRepository.getDefaultConfigPath().parent!!.resolve("trusted-certs")
	}
}
