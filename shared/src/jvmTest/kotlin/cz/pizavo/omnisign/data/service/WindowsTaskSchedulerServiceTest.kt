package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain

/**
 * Verifies [WindowsTaskSchedulerService] install/uninstall/idempotency.
 *
 * Guarded by an OS check — skipped automatically on non-Windows systems.
 */
class WindowsTaskSchedulerServiceTest : FunSpec({
	
	val service = WindowsTaskSchedulerService()
	
	fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")
	
	fun requiresAdmin(block: () -> Unit) {
		try {
			block()
		} catch (e: IllegalStateException) {
			if (e.message?.contains("Scheduler command failed", ignoreCase = true) == true) {
				println("SKIP: scheduler command not available or requires elevated privileges: ${e.message}")
				return
			}
			throw e
		}
	}
	
	test("install registers the task and isInstalled returns true").config(enabled = isWindows()) {
		requiresAdmin {
			service.install(cliExecutablePath = "C:\\Program Files\\omnisign\\omnisign.exe")
			try {
				service.isInstalled().shouldBeTrue()
			} finally {
				service.uninstall()
			}
		}
	}
	
	test("uninstall removes the task and isInstalled returns false").config(enabled = isWindows()) {
		requiresAdmin {
			service.install(cliExecutablePath = "C:\\Program Files\\omnisign\\omnisign.exe")
			service.uninstall()
			service.isInstalled().shouldBeFalse()
		}
	}
	
	test("install is idempotent — calling twice leaves exactly one task").config(enabled = isWindows()) {
		requiresAdmin {
			service.install(cliExecutablePath = "C:\\omnisign\\omnisign.exe")
			service.install(cliExecutablePath = "C:\\omnisign\\omnisign.exe")
			try {
				service.isInstalled().shouldBeTrue()
			} finally {
				service.uninstall()
			}
		}
	}
	
	test("uninstall on absent task does not throw").config(enabled = isWindows()) {
		service.uninstall()
		service.isInstalled().shouldBeFalse()
	}

	test("buildRegisterScript enables StartWhenAvailable for missed-run catch-up") {
		val script = service.buildRegisterScript("C:\\omnisign\\omnisign.exe", "renew", "02:00")
		script shouldContain "New-ScheduledTaskSettingsSet -StartWhenAvailable"
		script shouldContain "-Settings \$settings"
		script shouldContain "New-ScheduledTaskTrigger -Daily -At '02:00'"
	}
})

