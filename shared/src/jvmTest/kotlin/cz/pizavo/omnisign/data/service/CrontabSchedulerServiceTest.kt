package cz.pizavo.omnisign.data.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [CrontabSchedulerService].
 *
 * These tests manipulate the real user crontab, so they are guarded by an OS check and
 * skipped automatically on Windows.  They are integration-style but require no external
 * infrastructure beyond a working `crontab` binary.
 */
class CrontabSchedulerServiceTest {
	
	private val service = CrontabSchedulerService()
	
	private fun isUnix() = !System.getProperty("os.name", "").lowercase().contains("win")
	
	@Test
	fun `install adds a crontab entry and isInstalled returns true`() {
		if (!isUnix()) return
		
		service.install(cliExecutablePath = "/usr/bin/omnisign", runAtHour = 3, runAtMinute = 0)
		assertTrue(service.isInstalled(), "Expected job to be installed")
	}
	
	@Test
	fun `uninstall removes the crontab entry and isInstalled returns false`() {
		if (!isUnix()) return
		
		service.install(cliExecutablePath = "/usr/bin/omnisign")
		service.uninstall()
		assertFalse(service.isInstalled(), "Expected job to be absent after uninstall")
	}
	
	@Test
	fun `install is idempotent — calling twice does not create duplicate entries`() {
		if (!isUnix()) return
		
		service.install(cliExecutablePath = "/usr/bin/omnisign")
		service.install(cliExecutablePath = "/usr/bin/omnisign")
		
		val count = ProcessBuilder("crontab", "-l")
			.redirectErrorStream(true)
			.start()
			.inputStream.bufferedReader().readText()
			.lines()
			.count { it.contains(OsSchedulerService.JOB_TAG) }
		
		assertTrue(count == 1, "Expected exactly 1 crontab entry, found $count")
		
		service.uninstall()
	}
	
	@Test
	fun `uninstall on clean crontab does not throw`() {
		if (!isUnix()) return
		
		service.uninstall()
		assertFalse(service.isInstalled())
	}
}

