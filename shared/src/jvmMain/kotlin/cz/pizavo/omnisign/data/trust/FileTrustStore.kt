package cz.pizavo.omnisign.data.trust

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.data.repository.failureDetails
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
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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
 * An index file that cannot be read or decoded, or that is in a format version this build does
 * not read, as one a newer build wrote may be, is left as it is, and so is every certificate file:
 * each operation fails with [TrustStoreError.indexUnreadable] and nothing is cached, so the next
 * one reads the file again, and once it is restored or moved away the store works. Only a file the
 * file system reports missing counts as no index: one whose existence cannot be told, as in a
 * directory this user may not search, is one that cannot be read. Without an index file the store
 * starts empty, caches nothing and deletes nothing, as the certificate files it finds may be all
 * that is left of an index moved away, which may yet be put back. The certificate files that no
 * entry of an index read from its file names are deleted when the store first loads it. A
 * certificate file, too, is taken for missing only when the file system reports it missing: one
 * that cannot be read, or whose existence cannot be told, is kept and can be referenced, and
 * [resolve] leaves it out until it can be read.
 *
 * An error's details name the class of an I/O failure, as the message of one often holds only the
 * path, and give the message of any other failure.
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
			val index = ensureIndex().getOrElse { return@withLock it.left() }
			val der = x509.encoded
			val fingerprint = certFingerprint(der)
			val file = certFile(fingerprint)
			if (needsWriting(file, fingerprint)) writeFile(file, der)

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
				details = e.failureDetails(),
				cause = e,
			).left()
		}
	}

	override suspend fun remove(scope: TrustScope, fingerprint: String): OperationResult<Unit> =
		mutex.withLock {
			try {
				val index = ensureIndex().getOrElse { return@withLock it.left() }
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
				val index = ensureIndex().getOrElse { return@withLock it.left() }
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
			val index = ensureIndex().getOrElse { return@withLock it.left() }
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
				val index = ensureIndex().getOrElse { return@withLock it.left() }
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
				val index = ensureIndex().getOrElse { return@withLock it.left() }
				val merged = LinkedHashMap<String, TrustedCertificateType>()
				index.scopes[GLOBAL_KEY].orEmpty().forEach { merged[it.fingerprint] = it.type }
				if (scope != TrustScope.Global) {
					index.scopes[scope.key()].orEmpty().forEach { merged[it.fingerprint] = it.type }
				}
				merged.mapNotNull { (fingerprint, type) ->
					anchorBytes(fingerprint)?.let { ResolvedTrustAnchor(fingerprint, type, it) }
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
			val index = ensureIndex().getOrElse { return@withLock it.left() }
			if (fingerprint !in index.certs || !hasStoredCopy(fingerprint)) {
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
			ensureIndex().map { index ->
				index.certs.entries.firstOrNull { source in it.value.sources }?.key
			}
		} catch (e: Exception) {
			fail(e)
		}
	}

	override suspend fun scopes(): OperationResult<Set<TrustScope>> = mutex.withLock {
		try {
			ensureIndex().map { index ->
				index.scopes.keys.mapTo(mutableSetOf()) { key ->
					if (key == GLOBAL_KEY) TrustScope.Global else TrustScope.Profile(key.removePrefix("profile:"))
				}
			}
		} catch (e: Exception) {
			fail(e)
		}
	}

	/**
	 * Parse [bytes], PEM or DER, as one X.509 certificate.
	 */
	private fun parse(bytes: ByteArray): X509Certificate =
		bytes.inputStream().use {
			CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
		}

	/**
	 * The file that holds the certificate [fingerprint] names.
	 */
	private fun certFile(fingerprint: String): Path = trustDir.resolve("$fingerprint.der")

	/**
	 * Whether the certificate [fingerprint] names has to be written to [file]: the file system
	 * reports the file missing, or it holds other bytes, as a file left behind by an index that was
	 * moved away, and damaged since, may. A file that cannot be read now is left as it is, with a
	 * warning.
	 */
	private fun needsWriting(file: Path, fingerprint: String): Boolean =
		try {
			certFingerprint(file.readBytes()) != fingerprint
		} catch (_: NoSuchFileException) {
			true
		} catch (e: IOException) {
			logger.warn { "Could not read trust file for $fingerprint; leaving it as it is: $e" }
			false
		}

	/**
	 * Whether the certificate file [fingerprint] names is there, `false` only when the file system
	 * reports it missing: a file that cannot be read now, or whose existence cannot be told, is taken
	 * for one that is there, with a warning, as the load-time repair keeps it.
	 */
	private fun hasStoredCopy(fingerprint: String): Boolean =
		try {
			certFile(fingerprint).readBytes()
			true
		} catch (_: NoSuchFileException) {
			false
		} catch (e: IOException) {
			logger.warn { "Could not read trust file for $fingerprint; referencing it all the same: $e" }
			true
		}

	/**
	 * The bytes of the certificate file [fingerprint] names, or `null`, with a warning, when the file
	 * system reports it missing or it cannot be read now, so that the other anchors are still used.
	 * The warning is one line, without a stack trace, as every resolution gives it again, which on a
	 * server is every validation.
	 */
	private fun anchorBytes(fingerprint: String): ByteArray? =
		try {
			certFile(fingerprint).readBytes()
		} catch (_: NoSuchFileException) {
			logger.warn { "Trust file for $fingerprint is missing; skipping it" }
			null
		} catch (e: IOException) {
			logger.warn { "Could not read trust file for $fingerprint; skipping it: $e" }
			null
		}

	/**
	 * The key this scope's references are stored under in the index.
	 */
	private fun TrustScope.key(): String = when (this) {
		TrustScope.Global -> GLOBAL_KEY
		is TrustScope.Profile -> "profile:$name"
	}

	/**
	 * The error for a [fingerprint] that [scope] does not reference.
	 */
	private fun notFound(fingerprint: String, scope: TrustScope): Either<TrustStoreError, Nothing> =
		TrustStoreError.notFoundInScope(fingerprint, scope).left()

	/**
	 * The error for an operation that [e] cut short.
	 */
	private fun fail(e: Exception): Either<TrustStoreError, Nothing> =
		TrustStoreError.operationFailed(details = e.failureDetails(), cause = e).left()

	/**
	 * The certificate an index [entry] and a scope's [type] for it describe.
	 */
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

	/**
	 * The index: the cached one, or else the index file, read and reconciled with the directory, or
	 * an empty index when there is no index file. An index file that cannot be read gives an error
	 * and is neither cached nor touched, so the next call reads it again. The empty index of a
	 * missing file is not cached either, so an index file put in place before the next call is read
	 * by it rather than written over.
	 */
	private fun ensureIndex(): OperationResult<TrustStoreIndex> {
		cachedIndex?.let { return it.right() }
		val read = readIndex(trustDir.resolve(INDEX_FILE)).getOrElse { return it.left() }
			?: return TrustStoreIndex().right()
		val index = repair(read)
		cachedIndex = index
		return index.right()
	}

	/**
	 * Read and decode the index [file], refusing one in a format version other than
	 * [TrustStoreIndex.CURRENT_VERSION], or give `null` when the file system reports that there is
	 * no such file. A file whose existence cannot be told, as one in a directory this user may not
	 * search, is one that cannot be read, not a missing one. The warning of a file that cannot be read
	 * is one line, without a stack trace, as every operation reads the file again and gives it again.
	 */
	private fun readIndex(file: Path): OperationResult<TrustStoreIndex?> {
		val path = file.toAbsolutePath().toString()
		val index = try {
			cbor.decodeFromByteArray(TrustStoreIndex.serializer(), file.readBytes())
		} catch (_: NoSuchFileException) {
			return null.right()
		} catch (e: Exception) {
			logger.warn { "Could not read trust index $file; leaving the trust store as it is: $e" }
			return TrustStoreError.indexUnreadable(path, details = e.failureDetails(), cause = e).left()
		}
		if (index.version != TrustStoreIndex.CURRENT_VERSION) {
			logger.warn { "Trust index $file is in format version ${index.version}; leaving the trust store as it is" }
			return TrustStoreError.indexUnreadable(
				path,
				details = "Format version ${index.version}; this build reads version ${TrustStoreIndex.CURRENT_VERSION}",
			).left()
		}
		return index.right()
	}

	/**
	 * Reconcile [index], read from the index file, with the directory: drop the entries whose file
	 * the file system reports missing or that does not hold the certificate its name says, and
	 * delete the certificate files no entry names. An entry whose file cannot be read now, or whose
	 * existence cannot be told, is kept, as a later load may read it.
	 */
	private fun repair(index: TrustStoreIndex): TrustStoreIndex {
		if (!trustDir.exists()) return index
		val validCerts = index.certs.filterKeys { fingerprint ->
			val bytes = try {
				certFile(fingerprint).readBytes()
			} catch (_: NoSuchFileException) {
				logger.warn { "Trust index references missing file $fingerprint; dropping it" }
				return@filterKeys false
			} catch (e: IOException) {
				logger.warn { "Could not read trust file $fingerprint; keeping it: $e" }
				return@filterKeys true
			}
			val intact = certFingerprint(bytes) == fingerprint
			if (!intact) logger.warn { "Trust file $fingerprint fails its own checksum; dropping it" }
			intact
		}
		val validScopes = index.scopes
			.mapValues { (_, refs) -> refs.filter { it.fingerprint in validCerts } }
			.filterValues { it.isNotEmpty() }
		deleteOrphanFiles(validCerts.keys)
		val repaired = index.copy(certs = validCerts, scopes = validScopes)
		if (repaired != index) writeIndex(repaired)
		return repaired
	}

	/**
	 * Delete the certificate files in the directory whose fingerprint is not in [known].
	 */
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

	/**
	 * Write [index] to the index file and cache it.
	 */
	private fun persist(index: TrustStoreIndex) {
		writeIndex(index)
		cachedIndex = index
	}

	/**
	 * Replace the index file with [index] through a temporary file and a move, atomic where the
	 * file system supports it.
	 */
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

	/**
	 * Write the certificate [der] to [file], creating the directory when it does not exist yet.
	 */
	private fun writeFile(file: Path, der: ByteArray) {
		Files.createDirectories(trustDir)
		restrictDirPermissions(trustDir)
		Files.write(file, der)
		restrictFilePermissions(file)
	}

	/**
	 * Restrict the file at [path] to owner-read and owner-write (POSIX 600); ignored where the file
	 * system has no POSIX permissions.
	 */
	private fun restrictFilePermissions(path: Path) {
		try {
			Files.setPosixFilePermissions(
				path,
				setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
			)
		} catch (_: UnsupportedOperationException) {
		}
	}

	/**
	 * Restrict the directory at [path] to its owner (POSIX 700); ignored where the file system has
	 * no POSIX permissions.
	 */
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
