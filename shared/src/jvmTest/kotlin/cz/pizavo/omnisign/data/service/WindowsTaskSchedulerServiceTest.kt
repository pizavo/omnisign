package cz.pizavo.omnisign.data.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.encoding.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Verifies [WindowsTaskSchedulerService] install/uninstall/idempotency, and that the task's
 * command line keeps its quotes, from the registration script to the `cmd` line the task runs, when
 * the executable or the log file has a space in its path.
 *
 * The tests that reach Task Scheduler run only on Windows, and only for an account that may create
 * scheduled tasks. They work on a task with a random name instead of [OsSchedulerService.JOB_TAG],
 * so a renewal task the user installed is never replaced or removed, and they remove their task
 * after every test through `schtasks` itself rather than [WindowsTaskSchedulerService.uninstall],
 * so a fault in that method, such as one a mutation test plants, leaves no task behind.
 */
@OptIn(ExperimentalUuidApi::class)
class WindowsTaskSchedulerServiceTest : FunSpec({

	val taskName = "omnisign-test-${Uuid.random()}"
	val service = WindowsTaskSchedulerService(taskName = taskName)
	val spacedExe = "C:\\Users\\Jan Novak\\AppData\\Local\\omnisign-cli\\omnisign.exe"
	val spacedLog = "C:\\Users\\Jan Novak\\renewal logs\\renew.log"

	fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")

	/**
	 * Read the program and the arguments of the first action of the task this spec registers,
	 * through a PowerShell script handed over encoded, so that no quote in the answer is lost.
	 * PowerShell's progress records, which it writes to standard error when that is redirected, are
	 * turned off and discarded.
	 */
	fun registeredAction(): Pair<String, String> {
		val script = "\$ProgressPreference = 'SilentlyContinue'; " +
			"[Console]::OutputEncoding = [Text.Encoding]::UTF8; " +
			"\$action = (Get-ScheduledTask -TaskName '$taskName').Actions[0]; " +
			"\$action.Execute; \$action.Arguments"
		val encoded = Base64.Default.encode(script.toByteArray(Charsets.UTF_16LE))
		val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start()
		val lines = process.inputStream.readAllBytes().toString(Charsets.UTF_8).lines()
		process.waitFor()
		return lines[0] to lines[1]
	}

	/**
	 * Start [program] with [arguments] as the whole rest of its command line and wait for it, as
	 * Task Scheduler starts a task's action. PowerShell's `Start-Process` hands a single argument
	 * string to the program unchanged, where [ProcessBuilder] would quote it once more.
	 */
	fun startAsTaskSchedulerDoes(program: String, arguments: String) {
		val script = "\$ProgressPreference = 'SilentlyContinue'; " +
			"Start-Process -FilePath '${program.replace("'", "''")}' " +
			"-ArgumentList '${arguments.replace("'", "''")}' -Wait -NoNewWindow"
		val encoded = Base64.Default.encode(script.toByteArray(Charsets.UTF_16LE))
		val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
			.redirectErrorStream(true)
			.start()
		process.inputStream.readAllBytes()
		process.waitFor()
	}

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

	afterTest { if (isWindows()) schtasks("/delete", "/tn", taskName, "/f") }

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

	test("buildRegisterScript doubles a single quote in the executable and its arguments") {
		val script = service.buildRegisterScript("C:\\Users\\O'Brien\\omnisign.exe", "renew >> 'log'", "02:00")
		script shouldContain "-Execute 'C:\\Users\\O''Brien\\omnisign.exe'"
		script shouldContain "-Argument 'renew >> ''log'''"
	}

	test("buildAction runs the executable directly when there is no log file") {
		service.buildAction(spacedExe, null) shouldBe (spacedExe to "renew")
	}

	test("buildAction quotes both paths inside one outer pair of quotes that cmd strips") {
		service.buildAction(spacedExe, spacedLog) shouldBe
			("cmd" to "/s /c \"\"$spacedExe\" renew >> \"$spacedLog\" 2>&1\"")
	}

	test("buildInstallCommand hands the registration script to PowerShell encoded, quotes included") {
		val command = service.buildInstallCommand(spacedExe, 2, 5, spacedLog)

		command.take(4) shouldContainExactly listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand")
		command.size shouldBe 5
		val script = Base64.Default.decode(command[4]).toString(Charsets.UTF_16LE)
		val (exe, args) = service.buildAction(spacedExe, spacedLog)
		script shouldBe service.buildRegisterScript(exe, args, "02:05")
		script shouldContain "-Argument '/s /c \"\"$spacedExe\" renew >> \"$spacedLog\" 2>&1\"'"
	}

	test("install registers the action's arguments with their quotes intact").config(enabledIf = { canCreateTasks }) {
		service.install(cliExecutablePath = spacedExe, logFilePath = spacedLog)

		registeredAction() shouldBe service.buildAction(spacedExe, spacedLog)
	}

	test("install reports a registration PowerShell refuses as plain text, the value it refused included")
		.config(enabled = isWindows()) {
			val failure = shouldThrow<IllegalStateException> {
				service.install(cliExecutablePath = "C:\\omnisign\\omnisign.exe", runAtHour = 25, runAtMinute = 99)
			}

			failure.message shouldContain "25:99"
			failure.message shouldNotContain "CLIXML"
			failure.message shouldNotContain "_x000D__x000A_"
			failure.message shouldNotContain "\uFFFD"
			service.isInstalled().shouldBeFalse()
		}

	test("the action's command line runs a program and logs its output when both paths have spaces")
		.config(enabled = isWindows()) {
			val dir = (tempdir().toPath() / "with space").createDirectories()
			val program = (dir / "fake renewal.cmd").also { it.writeText("@echo ran %*\r\n") }
			val log = dir / "renew log.txt"
			val (exe, args) = service.buildAction(program.toString(), log.toString())

			startAsTaskSchedulerDoes(exe, args)

			log.readText().trim() shouldBe "ran renew"
		}
})
