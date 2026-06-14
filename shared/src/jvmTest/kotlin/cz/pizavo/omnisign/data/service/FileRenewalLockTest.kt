package cz.pizavo.omnisign.data.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Verifies [FileRenewalLock]'s single-host mutual-exclusion semantics.
 */
class FileRenewalLockTest : FunSpec({

	test("grants the lock, refuses a second concurrent acquirer, and re-grants after release") {
		val lockFile = tempdir().toPath() / "renewal.lock"
		val lock = FileRenewalLock(lockFile)

		val first = lock.tryAcquire()
		first.shouldNotBeNull()

		lock.tryAcquire().shouldBeNull()

		first.close()

		val second = lock.tryAcquire()
		second.shouldNotBeNull()
		second.close()
	}

	test("creates the lock file's parent directories when they are missing") {
		val lockFile = tempdir().toPath() / "nested" / "deep" / "renewal.lock"
		val lock = FileRenewalLock(lockFile)

		val handle = lock.tryAcquire()
		handle.shouldNotBeNull()
		handle.close()
	}

	test("throws when the lock file's location cannot be created") {
		val occupied = tempdir().toPath() / "occupied"
		occupied.writeText("not a directory")
		val lockFile = occupied / "renewal.lock"
		val lock = FileRenewalLock(lockFile)

		shouldThrow<Exception> { lock.tryAcquire() }
	}
})
