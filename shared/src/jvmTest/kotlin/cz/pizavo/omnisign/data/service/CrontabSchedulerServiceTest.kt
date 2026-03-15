package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * Verifies [CrontabSchedulerService] install/uninstall/idempotency.
 *
 * Guarded by an OS check — skipped automatically on Windows.
 */
class CrontabSchedulerServiceTest : FunSpec({
	
	val service = CrontabSchedulerService()
	
	fun isUnix() = !System.getProperty("os.name", "").lowercase().contains("win")
	
	test("install adds a crontab entry and isInstalled returns true").config(enabled = isUnix()) {
		service.install(cliExecutablePath = "/usr/bin/omnisign", runAtHour = 3, runAtMinute = 0)
		service.isInstalled().shouldBeTrue()
	}
	
	test("uninstall removes the crontab entry and isInstalled returns false").config(enabled = isUnix()) {
		service.install(cliExecutablePath = "/usr/bin/omnisign")
		service.uninstall()
		service.isInstalled().shouldBeFalse()
	}
	
	test("install is idempotent — calling twice does not create duplicate entries").config(enabled = isUnix()) {
		service.install(cliExecutablePath = "/usr/bin/omnisign")
		service.install(cliExecutablePath = "/usr/bin/omnisign")
		
		val count = ProcessBuilder("crontab", "-l")
			.redirectErrorStream(true)
			.start()
			.inputStream.bufferedReader().readText()
			.lines()
			.count { it.contains(OsSchedulerService.JOB_TAG) }
		
		count shouldBe 1
		
		service.uninstall()
	}
	
	test("uninstall on clean crontab does not throw").config(enabled = isUnix()) {
		service.uninstall()
		service.isInstalled().shouldBeFalse()
	}
})

