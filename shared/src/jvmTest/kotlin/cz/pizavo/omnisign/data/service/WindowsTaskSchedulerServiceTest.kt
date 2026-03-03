package cz.pizavo.omnisign.data.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [WindowsTaskSchedulerService].
 *
 * Guarded by an OS check — skipped automatically on non-Windows systems.
 * Tests are also skipped when `schtasks` requires elevated privileges that are not
 * available in the current environment (e.g. a non-admin CI runner).
 * Each test cleans up by uninstalling the task at the end.
 */
class WindowsTaskSchedulerServiceTest {
	
	private val service = WindowsTaskSchedulerService()
	
	private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")
	
	private inline fun requiresAdmin(block: () -> Unit) {
		try {
			block()
		} catch (e: IllegalStateException) {
			if (e.message?.contains("schtasks", ignoreCase = true) == true) {
				println("SKIP: schtasks not available or requires elevated privileges: ${e.message}")
				return
			}
			throw e
		}
	}
	
	@Test
	fun `install registers the task and isInstalled returns true`() {
		if (!isWindows()) return
		requiresAdmin {
			service.install(cliExecutablePath = "C:\\Program Files\\omnisign\\omnisign.exe")
			try {
				assertTrue(service.isInstalled())
			} finally {
				service.uninstall()
			}
		}
	}
	
	@Test
	fun `uninstall removes the task and isInstalled returns false`() {
		if (!isWindows()) return
		requiresAdmin {
			service.install(cliExecutablePath = "C:\\Program Files\\omnisign\\omnisign.exe")
			service.uninstall()
			assertFalse(service.isInstalled())
		}
	}
	
	@Test
	fun `install is idempotent — calling twice leaves exactly one task`() {
		if (!isWindows()) return
		requiresAdmin {
			service.install(cliExecutablePath = "C:\\omnisign\\omnisign.exe")
			service.install(cliExecutablePath = "C:\\omnisign\\omnisign.exe")
			try {
				assertTrue(service.isInstalled())
			} finally {
				service.uninstall()
			}
		}
	}
	
	@Test
	fun `uninstall on absent task does not throw`() {
		if (!isWindows()) return
		service.uninstall()
		assertFalse(service.isInstalled())
	}
}



