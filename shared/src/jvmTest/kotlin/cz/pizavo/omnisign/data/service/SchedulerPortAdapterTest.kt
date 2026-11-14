package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk

/**
 * Verifies that [SchedulerPortAdapter] passes on what the [OsSchedulerService] it wraps tells.
 */
class SchedulerPortAdapterTest : FunSpec({

	test("isAvailable passes on whether the OS scheduler can register the job") {
		val service = mockk<OsSchedulerService>()
		every { service.isAvailable() } returnsMany listOf(true, false)
		val adapter = SchedulerPortAdapter(service)

		adapter.isAvailable().shouldBeTrue()
		adapter.isAvailable().shouldBeFalse()
	}
})
