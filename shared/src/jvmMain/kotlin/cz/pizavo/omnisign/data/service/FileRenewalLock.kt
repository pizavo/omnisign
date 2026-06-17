package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.port.RenewalLock
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createParentDirectories

private val logger = KotlinLogging.logger {}

/**
 * [RenewalLock] backed by an exclusive OS advisory lock ([FileChannel.tryLock]) on a single lock
 * file — by convention `renewal.lock` beside the application configuration.
 *
 * A second renewal process (CLI or headless desktop) that starts while one is still running fails
 * to acquire the lock and skips, so the same documents are never re-timestamped concurrently. The
 * lock is released when the returned handle is closed and, as a backstop, by the OS when the
 * holding process exits.
 *
 * If the lock file cannot be created or locked at all — an unusual filesystem, a permission
 * problem — acquisition **fails closed**: the cause is logged and rethrown so the caller aborts the
 * run and reports the failure, rather than re-timestamping documents without the lock's protection.
 * Genuine contention (another run already holds the lock) is *not* an error: it returns `null`.
 *
 * @param lockFile Absolute path to the lock file.
 */
class FileRenewalLock(private val lockFile: Path) : RenewalLock {

	override fun tryAcquire(): AutoCloseable? {
		val channel = try {
			lockFile.createParentDirectories()
			FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
		} catch (e: Exception) {
			logger.error(e) { "Could not open renewal lock file $lockFile" }
			throw e
		}
		return try {
			val lock = channel.tryLock()
			if (lock == null) {
				channel.close()
				null
			} else {
				AutoCloseable {
					channel.use { lock.release() }
				}
			}
		} catch (_: OverlappingFileLockException) {
			channel.close()
			null
		} catch (e: Exception) {
			channel.close()
			logger.error(e) { "Could not acquire renewal lock $lockFile" }
			throw e
		}
	}
}
