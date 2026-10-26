package cz.pizavo.omnisign.data.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import org.slf4j.LoggerFactory

/**
 * Verifies [KeyringCredentialStore]'s in-memory fallback and what it logs about it.
 *
 * `memoryOnlyStore` makes both keychain backends unavailable, so these tests run the fallback on
 * every OS and never touch the keychain of the machine running them. The keychain paths
 * themselves (java-keyring on Windows/macOS, purejava/secret-service on Linux) depend on the host
 * and its desktop session and are not unit-testable in isolation.
 */
class KeyringCredentialStoreTest : FunSpec({

	val loggerName = "cz.pizavo.omnisign.data.service.KeyringCredentialStore"

	/**
	 * Build a store whose java-keyring and Secret Service backends both report themselves
	 * unavailable, as they do in a container.
	 */
	fun memoryOnlyStore(): KeyringCredentialStore {
		mockkStatic(Keyring::class)
		mockkConstructor(FreedesktopSecretServiceStore::class)
		try {
			every { Keyring.create() } throws BackendNotSupportedException("no keychain in tests")
			every { anyConstructed<FreedesktopSecretServiceStore>().isAvailable } returns false
			return KeyringCredentialStore()
		} finally {
			unmockkConstructor(FreedesktopSecretServiceStore::class)
			unmockkStatic(Keyring::class)
		}
	}

	/** Run [block] and return every event the store's logger emitted meanwhile, down to DEBUG. */
	fun eventsLoggedDuring(block: () -> Unit): List<ILoggingEvent> {
		val logbackLogger = LoggerFactory.getLogger(loggerName) as Logger
		val previousLevel = logbackLogger.level
		val appender = ListAppender<ILoggingEvent>().also {
			it.start()
			logbackLogger.addAppender(it)
		}
		logbackLogger.level = Level.DEBUG
		try {
			block()
		} finally {
			logbackLogger.level = previousLevel
			logbackLogger.detachAppender(appender)
			appender.stop()
		}
		return appender.list.toList()
	}

	test("keeps a password in memory, and says it does not persist, when no keychain is available") {
		val store = memoryOnlyStore()

		store.setPassword("svc", "acct", "secret")

		store.getPassword("svc", "acct") shouldBe "secret"
		store.isPersistent.shouldBeFalse()
	}

	test("returns null for a password it never stored") {
		memoryOnlyStore().getPassword("svc", "missing").shouldBeNull()
	}

	test("deletes a stored password") {
		val store = memoryOnlyStore()
		store.setPassword("svc", "acct", "secret")

		store.deletePassword("svc", "acct")

		store.getPassword("svc", "acct").shouldBeNull()
	}

	test("deleting a password it never stored is safe") {
		shouldNotThrowAny { memoryOnlyStore().deletePassword("svc", "ghost") }
	}

	test("logs the fallback at INFO when it is built, not as a warning") {
		val events = eventsLoggedDuring { memoryOnlyStore() }

		events.filter { it.level == Level.WARN }.shouldBeEmpty()
		events.filter { it.level == Level.INFO }.also { infos ->
			infos.shouldHaveSize(1)
			infos.single().formattedMessage shouldContain "no keyring service was found"
		}
	}

	test("stays quiet when passwords are only read") {
		val store = memoryOnlyStore()

		val events = eventsLoggedDuring { store.getPassword("omnisign-token", "token-1") }

		events.filter { it.level == Level.WARN }.shouldBeEmpty()
	}

	test("warns once, on the first password it keeps in memory") {
		val store = memoryOnlyStore()

		val events = eventsLoggedDuring {
			store.setPassword("svc", "first", "secret")
			store.setPassword("svc", "second", "secret")
		}

		events.filter { it.level == Level.WARN }.also { warnings ->
			warnings.shouldHaveSize(1)
			warnings.single().formattedMessage shouldContain "No keyring service was found"
			warnings.single().formattedMessage shouldContain "GNOME Keyring, KWallet or KeePassXC"
		}
	}

	test("builds against the host's own keychain without throwing") {
		shouldNotThrowAny { KeyringCredentialStore().isPersistent }
	}
})
