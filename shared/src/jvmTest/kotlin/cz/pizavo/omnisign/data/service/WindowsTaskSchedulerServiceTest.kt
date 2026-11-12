package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Verifies [WindowsTaskSchedulerService] install/uninstall/idempotency.
 *
 * The tests that reach Task Scheduler run only on Windows, and only for an account that may create
 * scheduled tasks. They work on a task with a random name instead of [OsSchedulerService.JOB_TAG],
 * so a renewal task the user installed is never replaced or removed, and they remove their task
 * after every test.
 */
@OptIn(ExperimentalUuidApi::class)
class WindowsTaskSchedulerServiceTest : FunSpec({

	val service = WindowsTaskSchedulerService(taskName = "omnisign-test-${Uuid.random()}")

	fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")

	/**
	 * Run `schtasks` with [args], discarding its output.
	 *
	 * @return The exit code of `schtasks`.
	 */
	fun schtasks(vararg args: String): Int {
		val process = ProcessBuilder("schtasks", *args).redirectErrorStream(true).start()
		process.inputStream.readAllBytes()
		return process.waitFor()
	}

	/**
	 * Whether this account may create scheduled tasks.
	 *
	 * Found by creating and deleting a throwaway task through `schtasks` rather than through
	 * [WindowsTaskSchedulerService], so that a fault in the service fails the tests below instead
	 * of disabling them.
	 */
	val canCreateTasks by lazy {
		isWindows() && run {
			val probe = "omnisign-test-probe-${Uuid.random()}"
			val created = schtasks("/create", "/tn", probe, "/tr", "cmd /c exit 0", "/sc", "daily", "/st", "02:00", "/f") == 0
			if (created) schtasks("/delete", "/tn", probe, "/f")
			created
		}
	}

	afterTest { if (isWindows()) service.uninstall() }

	test("install registers the task and isInstalled returns true").config(enabledIf = { canCreateTasks }) {
		service.install(cliExecutablePath = "C:\\Program Files\\omnisign\\omnisign.exe")
		service.isInstalled().shouldBeTrue()
	}

	test("uninstall removes the task and isInstalled returns false").config(enabledIf = { canCreateTasks }) {
		service.install(cliExecutablePath = "C:\\Program Files\\omnisign\\omnisign.exe")
		service.uninstall()
		service.isInstalled().shouldBeFalse()
	}

	test("install is idempotent — calling twice leaves exactly one task").config(enabledIf = { canCreateTasks }) {
		service.install(cliExecutablePath = "C:\\omnisign\\omnisign.exe")
		service.install(cliExecutablePath = "C:\\omnisign\\omnisign.exe")
		service.isInstalled().shouldBeTrue()
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

	test("buildRegisterScript registers the renewal job's task name by default") {
		val script = WindowsTaskSchedulerService().buildRegisterScript("C:\\omnisign\\omnisign.exe", "renew", "02:00")
		script shouldContain "-TaskName '${OsSchedulerService.JOB_TAG}'"
	}

	test("buildRegisterScript quotes the task name it was given") {
		val script = WindowsTaskSchedulerService(taskName = "it's a test")
			.buildRegisterScript("C:\\omnisign\\omnisign.exe", "renew", "02:00")
		script shouldContain "-TaskName 'it''s a test'"
	}
})
