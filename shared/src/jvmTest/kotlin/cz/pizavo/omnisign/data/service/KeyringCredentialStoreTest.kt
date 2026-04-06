package cz.pizavo.omnisign.data.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory

/**
 * Verifies [KeyringCredentialStore] in-memory fallback behaviour and warning log
 * emission when the native OS keychain is unavailable.
 *
 * These tests exercise the in-memory fallback path. The native keychain path
 * depends on the host OS and desktop session and is not unit-testable in isolation.
 */
class KeyringCredentialStoreTest : FunSpec({

	test("in-memory fallback round-trips setPassword and getPassword") {
		val store = KeyringCredentialStore()
		if (store.isNativeBackendAvailable) return@test

		store.setPassword("svc", "acct", "secret")
		store.getPassword("svc", "acct") shouldBe "secret"
	}

	test("in-memory fallback getPassword returns null for unknown entry") {
		val store = KeyringCredentialStore()
		if (store.isNativeBackendAvailable) return@test

		store.getPassword("svc", "missing").shouldBeNull()
	}

	test("in-memory fallback deletePassword removes stored entry") {
		val store = KeyringCredentialStore()
		if (store.isNativeBackendAvailable) return@test

		store.setPassword("svc", "acct", "secret")
		store.deletePassword("svc", "acct")
		store.getPassword("svc", "acct").shouldBeNull()
	}

	test("in-memory fallback deletePassword is safe for non-existent entry") {
		val store = KeyringCredentialStore()
		if (store.isNativeBackendAvailable) return@test

		store.deletePassword("svc", "ghost")
	}

	test("warns when native keychain is unavailable") {
		val loggerName = "cz.pizavo.omnisign.data.service.KeyringCredentialStore"
		val logbackLogger = LoggerFactory.getLogger(loggerName) as Logger
		val appender = ListAppender<ILoggingEvent>().also {
			it.start()
			logbackLogger.addAppender(it)
		}

		try {
			val store = KeyringCredentialStore()
			if (store.isNativeBackendAvailable) return@test

			appender.list.filter {
				it.level.toString() == "WARN"
			}.also { warnings ->
				warnings.shouldHaveSize(1)
				warnings.first().formattedMessage shouldContain "Native OS keychain is not available"
			}
		} finally {
			logbackLogger.detachAppender(appender)
			appender.stop()
		}
	}

	test("isNativeBackendAvailable returns a boolean without throwing") {
		val store = KeyringCredentialStore()
		(store.isNativeBackendAvailable || !store.isNativeBackendAvailable).shouldBeTrue()
	}
})

