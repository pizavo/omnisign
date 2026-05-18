package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Clock

/**
 * Verifies the per-identity refcounting of [TrustedListRefreshSignal]: an id is
 * "running" while at least one refresh holds it, ids are independent, and the
 * last-refreshed stamp is published.
 */
class TrustedListRefreshSignalTest : FunSpec({

	val custom = TrustedSourceId.CustomList("https://example.test/tl.xml", null)

	test("begin adds and end removes an id from running") {
		val signal = TrustedListRefreshSignal()
		signal.running.value.shouldBeEmpty()

		signal.begin(TrustedSourceId.EuLotl)
		signal.running.value shouldContainExactly setOf(TrustedSourceId.EuLotl)

		signal.end(TrustedSourceId.EuLotl)
		signal.running.value.shouldBeEmpty()
	}

	test("refcount keeps an id running until the last concurrent refresh ends") {
		val signal = TrustedListRefreshSignal()
		signal.begin(TrustedSourceId.EuLotl)
		signal.begin(TrustedSourceId.EuLotl)

		signal.end(TrustedSourceId.EuLotl)
		signal.running.value shouldContainExactly setOf(TrustedSourceId.EuLotl)

		signal.end(TrustedSourceId.EuLotl)
		signal.running.value.shouldBeEmpty()
	}

	test("ids are tracked independently") {
		val signal = TrustedListRefreshSignal()
		signal.begin(TrustedSourceId.EuLotl)
		signal.begin(custom)

		signal.running.value shouldBe setOf(TrustedSourceId.EuLotl, custom)

		signal.end(TrustedSourceId.EuLotl)
		signal.running.value shouldContainExactly setOf(custom)
	}

	test("markRefreshed publishes the last refresh timestamp") {
		val signal = TrustedListRefreshSignal()
		signal.lastRefreshAt.value shouldBe null

		val now = Clock.System.now()
		signal.markRefreshed(now)
		signal.lastRefreshAt.value.shouldNotBeNull() shouldBe now
	}
})
