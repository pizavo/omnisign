package cz.pizavo.omnisign.data.service

import eu.europa.esig.dss.token.AbstractSignatureTokenConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify

/**
 * Verifies [Pkcs11SessionCache] storage and close-on-eviction semantics. The held tokens are
 * relaxed MockK [AbstractSignatureTokenConnection]s so `close()` can be verified without real
 * PKCS#11 hardware.
 */
class Pkcs11SessionCacheTest : FunSpec({

	fun session(token: AbstractSignatureTokenConnection) =
		Pkcs11SessionCache.CachedSession(token, emptyList())

	test("get returns null for a token that is not unlocked") {
		Pkcs11SessionCache().get("absent").shouldBeNull()
	}

	test("put then get returns the stored session") {
		val cache = Pkcs11SessionCache()
		val stored = session(mockk(relaxed = true))
		cache.put("t1", stored)
		cache.get("t1") shouldBe stored
	}

	test("put for an existing id closes the superseded token and keeps the new one") {
		val cache = Pkcs11SessionCache()
		val old = mockk<AbstractSignatureTokenConnection>(relaxed = true)
		val fresh = mockk<AbstractSignatureTokenConnection>(relaxed = true)
		cache.put("t1", session(old))
		cache.put("t1", session(fresh))
		verify(exactly = 1) { old.close() }
		verify(exactly = 0) { fresh.close() }
		cache.get("t1") shouldBe session(fresh)
	}

	test("invalidate closes and drops the session for that id") {
		val cache = Pkcs11SessionCache()
		val token = mockk<AbstractSignatureTokenConnection>(relaxed = true)
		cache.put("t1", session(token))
		cache.invalidate("t1")
		verify(exactly = 1) { token.close() }
		cache.get("t1").shouldBeNull()
	}

	test("invalidateAll closes and drops every held session") {
		val cache = Pkcs11SessionCache()
		val a = mockk<AbstractSignatureTokenConnection>(relaxed = true)
		val b = mockk<AbstractSignatureTokenConnection>(relaxed = true)
		cache.put("a", session(a))
		cache.put("b", session(b))
		cache.invalidateAll()
		verify { a.close() }
		verify { b.close() }
		cache.get("a").shouldBeNull()
		cache.get("b").shouldBeNull()
	}

	test("close closes every held session") {
		val cache = Pkcs11SessionCache()
		val token = mockk<AbstractSignatureTokenConnection>(relaxed = true)
		cache.put("t1", session(token))
		cache.close()
		verify { token.close() }
	}
})
