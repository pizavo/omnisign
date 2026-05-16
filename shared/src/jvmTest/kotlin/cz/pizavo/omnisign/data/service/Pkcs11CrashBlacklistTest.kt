package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verifies the count-thresholded sliding-window semantics of [Pkcs11CrashBlacklist].
 *
 * Earlier revisions blacklisted a library after a single crash for the lifetime of the JVM,
 * which made one transient SafeNet `eTPKCS11.dll` SIGSEGV (a documented behaviour under
 * subprocess contention) silently disable the token until app restart.  The current model
 * accumulates crashes inside a sliding window and prunes stale records on read.
 */
class Pkcs11CrashBlacklistTest : FunSpec({

	/**
	 * Stepwise [Clock] backed by a mutable [Instant], so tests can advance virtual time
	 * without sleeping.
	 */
	class FakeClock(private var now: Instant) : Clock {
		override fun now(): Instant = now
		fun advanceBy(delta: kotlin.time.Duration) {
			now += delta
		}
	}

	test("isCrashed is false before any crash is recorded") {
		val blacklist = Pkcs11CrashBlacklist()
		blacklist.isCrashed("/lib.so").shouldBeFalse()
	}

	test("isCrashed stays false until the threshold count is reached inside the window") {
		val clock = FakeClock(Instant.fromEpochMilliseconds(0))
		val blacklist = Pkcs11CrashBlacklist(
			crashWindow = 5.minutes,
			crashThreshold = 3,
			clock = clock,
		)

		blacklist.registerCrashed("/lib.so")
		blacklist.isCrashed("/lib.so").shouldBeFalse()

		blacklist.registerCrashed("/lib.so")
		blacklist.isCrashed("/lib.so").shouldBeFalse()

		blacklist.registerCrashed("/lib.so")
		blacklist.isCrashed("/lib.so").shouldBeTrue()
	}

	test("isCrashed decays once the window elapses and accepts new probes") {
		val clock = FakeClock(Instant.fromEpochMilliseconds(0))
		val blacklist = Pkcs11CrashBlacklist(
			crashWindow = 5.minutes,
			crashThreshold = 1,
			clock = clock,
		)

		blacklist.registerCrashed("/lib.so")
		blacklist.isCrashed("/lib.so").shouldBeTrue()

		clock.advanceBy(5.minutes + 1.seconds)

		// Reading after the window prunes the stale record so the next probe runs fresh.
		blacklist.isCrashed("/lib.so").shouldBeFalse()
	}

	test("registerCrashed restarts the window when called after the previous record decayed") {
		val clock = FakeClock(Instant.fromEpochMilliseconds(0))
		val blacklist = Pkcs11CrashBlacklist(
			crashWindow = 5.minutes,
			crashThreshold = 2,
			clock = clock,
		)

		blacklist.registerCrashed("/lib.so")
		clock.advanceBy(6.minutes)
		blacklist.registerCrashed("/lib.so")

		// Previous crash decayed; the new register opens a fresh window with count = 1.
		blacklist.isCrashed("/lib.so").shouldBeFalse()

		blacklist.registerCrashed("/lib.so")
		blacklist.isCrashed("/lib.so").shouldBeTrue()
	}

	test("records are tracked independently per library path") {
		val clock = FakeClock(Instant.fromEpochMilliseconds(0))
		val blacklist = Pkcs11CrashBlacklist(
			crashWindow = 5.minutes,
			crashThreshold = 2,
			clock = clock,
		)

		blacklist.registerCrashed("/lib-a.so")
		blacklist.registerCrashed("/lib-a.so")
		blacklist.registerCrashed("/lib-b.so")

		blacklist.isCrashed("/lib-a.so").shouldBeTrue()
		blacklist.isCrashed("/lib-b.so").shouldBeFalse()
	}
})
