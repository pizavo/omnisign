package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies how [ResolvedConfig.resolve] merges an operation's timestamp server override onto the
 * stored one, and which stored credentials reach which server: the stored username, credential key
 * and in-memory password go along only while the override names the stored server, the same scheme,
 * host and port. A field the override leaves out keeps the stored value and a field it gives
 * replaces it, a timeout equal to the default included.
 */
class ResolvedTimestampServerTest : FunSpec({

	val storedUrl = "https://tsa.vendor-a.example/tsr"

	val stored = TimestampServerConfig(
		url = storedUrl,
		username = "alice",
		credentialKey = "alice",
		timeout = 60000,
		runtimePassword = Sensitive("vendor-a-secret"),
	)

	/** Resolve a configuration that stores [base] with [override] as the operation's timestamp server. */
	fun resolveWith(override: TimestampServerOverride, base: TimestampServerConfig? = stored) =
		ResolvedConfig.resolve(
			global = GlobalConfig(timestampServer = base),
			profile = null,
			operationOverrides = OperationConfig(timestampServer = override),
		).getOrElse { error("resolution failed: $it") }.timestampServer

	/** What [stored] resolves to when an override sends it to [url] and adds nothing else. */
	fun storedAt(url: String) = stored.copy(url = url)

	/** What an override naming only [url] resolves to when none of the stored credentials go along. */
	fun withoutStoredCredentials(url: String) = TimestampServerConfig(url = url, timeout = 60000)

	test("an override naming another host takes none of the stored credentials") {
		val url = "http://freetsa.example/tsr"
		resolveWith(TimestampServerOverride(url = url)) shouldBe withoutStoredCredentials(url)
	}

	test("an override naming another port or scheme of the stored host takes none of the stored credentials") {
		val urls = listOf(
			"https://tsa.vendor-a.example:8443/tsr",
			"http://tsa.vendor-a.example/tsr",
			"http://tsa.vendor-a.example:443/tsr",
		)
		for (url in urls) {
			resolveWith(TimestampServerOverride(url = url)) shouldBe withoutStoredCredentials(url)
		}
	}

	test("an override hiding another host behind user information takes none of the stored credentials") {
		val urls = listOf(
			"https://tsa.vendor-a.example@evil.example/tsr",
			"https://tsa.vendor-a.example:443@evil.example/tsr",
			"https://evil.example\\@tsa.vendor-a.example/tsr",
			"https://user@evil.example@tsa.vendor-a.example/tsr",
		)
		for (url in urls) {
			resolveWith(TimestampServerOverride(url = url)) shouldBe withoutStoredCredentials(url)
		}
	}

	test("an override naming another host before a slash, question mark or hash that the stored host follows takes none of the stored credentials") {
		val urls = listOf(
			"https://evil.example/@tsa.vendor-a.example/tsr",
			"https://evil.example?@tsa.vendor-a.example/tsr",
			"https://evil.example#@tsa.vendor-a.example/tsr",
		)
		for (url in urls) {
			resolveWith(TimestampServerOverride(url = url)) shouldBe withoutStoredCredentials(url)
		}
	}

	test("an override naming the stored server with user information in front of its host keeps the stored credentials") {
		val url = "https://bob@tsa.vendor-a.example/other"
		resolveWith(TimestampServerOverride(url = url)) shouldBe storedAt(url)
	}

	test("an override naming a stored http server with port 80 written out keeps the stored credentials") {
		val base = stored.copy(url = "http://tsa.vendor-a.example/tsr")
		val url = "http://tsa.vendor-a.example:80/other"
		resolveWith(TimestampServerOverride(url = url), base) shouldBe base.copy(url = url)
	}

	test("an override naming the stored server at another path keeps the stored credentials") {
		val url = "https://tsa.vendor-a.example/other-policy?profile=qualified"
		resolveWith(TimestampServerOverride(url = url)) shouldBe storedAt(url)
	}

	test("an override naming the stored server keeps the stored credentials whatever the case of its scheme and host and with its default port written out") {
		val url = "HTTPS://TSA.Vendor-A.example:443/tsr"
		resolveWith(TimestampServerOverride(url = url)) shouldBe storedAt(url)
	}

	test("an override naming a server at a bracketed IPv6 address keeps the stored credentials only for that address and port") {
		val base = stored.copy(url = "http://[2001:db8::1]:8080/tsr")
		val sameServer = "http://[2001:DB8::1]:8080/other"
		val otherServer = "http://[2001:db8::2]:8080/tsr"
		resolveWith(TimestampServerOverride(url = sameServer), base) shouldBe base.copy(url = sameServer)
		resolveWith(TimestampServerOverride(url = otherServer), base) shouldBe withoutStoredCredentials(otherServer)
	}

	test("an override with its own credentials for another host sends only those") {
		val url = "https://tsa.vendor-b.example/tsr"
		val override = TimestampServerOverride(url = url, username = "bob", runtimePassword = Sensitive("vendor-b-secret"))
		resolveWith(override) shouldBe
				TimestampServerConfig(url = url, username = "bob", timeout = 60000, runtimePassword = Sensitive("vendor-b-secret"))
	}

	test("an override with credentials and no URL keeps the stored server and the stored credentials it does not replace") {
		for (url in listOf(null, "", " ")) {
			val override = TimestampServerOverride(url = url, username = "bob", runtimePassword = Sensitive("bob-secret"))
			resolveWith(override) shouldBe stored.copy(username = "bob", runtimePassword = Sensitive("bob-secret"))
		}
	}

	test("an override naming a server takes none of the credentials stored without one") {
		val url = "https://tsa.vendor-a.example/tsr"
		resolveWith(TimestampServerOverride(url = url), base = stored.copy(url = "")) shouldBe
				withoutStoredCredentials(url)
	}

	test("an override of a URL whose origin cannot be read keeps the stored credentials only when it repeats the stored URL") {
		val base = stored.copy(url = "https://tsa_vendor.example/tsr")
		resolveWith(TimestampServerOverride(url = "https://tsa_vendor.example/tsr"), base) shouldBe base
		resolveWith(TimestampServerOverride(url = "https://tsa_vendor.example/other"), base) shouldBe
				withoutStoredCredentials("https://tsa_vendor.example/other")
	}

	test("an override naming a server whose scheme has no known default port keeps the stored credentials only with the same port written out") {
		val withPort = stored.copy(url = "ftp://tsa.vendor-a.example:2121/tsr")
		resolveWith(TimestampServerOverride(url = "ftp://tsa.vendor-a.example:2121/other"), withPort) shouldBe
				withPort.copy(url = "ftp://tsa.vendor-a.example:2121/other")
		resolveWith(TimestampServerOverride(url = "ftp://tsa.vendor-a.example/other"), withPort) shouldBe
				withoutStoredCredentials("ftp://tsa.vendor-a.example/other")
		val withoutPort = stored.copy(url = "ftp://tsa.vendor-a.example/tsr")
		resolveWith(TimestampServerOverride(url = "ftp://tsa.vendor-a.example/other"), withoutPort) shouldBe
				withoutStoredCredentials("ftp://tsa.vendor-a.example/other")
	}

	test("an override of the timeout alone replaces the stored timeout, even with the default value") {
		for (timeout in listOf(TimestampServerConfig.DEFAULT_TIMEOUT_MS, 5000)) {
			resolveWith(TimestampServerOverride(timeout = timeout)) shouldBe stored.copy(timeout = timeout)
		}
	}

	test("an override naming another server keeps the stored timeout only when it gives none") {
		val url = "https://tsa.vendor-b.example/tsr"
		resolveWith(TimestampServerOverride(url = url, timeout = TimestampServerConfig.DEFAULT_TIMEOUT_MS)) shouldBe
				TimestampServerConfig(url = url, timeout = TimestampServerConfig.DEFAULT_TIMEOUT_MS)
		resolveWith(TimestampServerOverride(url = url)) shouldBe withoutStoredCredentials(url)
	}

	test("an override that sets nothing resolves to the stored server unchanged") {
		resolveWith(TimestampServerOverride()) shouldBe stored
	}

	test("with no server stored, an override naming one gets the default timeout unless it gives its own, and one naming none resolves to none") {
		val url = "https://tsa.vendor-b.example/tsr"
		resolveWith(TimestampServerOverride(url = url, username = "bob"), base = null) shouldBe
				TimestampServerConfig(url = url, username = "bob")
		resolveWith(TimestampServerOverride(url = url, timeout = 5000), base = null) shouldBe
				TimestampServerConfig(url = url, timeout = 5000)
		resolveWith(TimestampServerOverride(username = "bob", timeout = 5000), base = null) shouldBe null
	}
})
