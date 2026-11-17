package cz.pizavo.omnisign.data.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * Verifies the unit-file rendering, the log file checks, the escaping of the paths, the
 * no-systemd guidance and the systemd probe of [SystemdSchedulerService].
 *
 * The install, uninstall and probe tests give the service a stand-in for `systemctl` that records
 * its arguments and exits with a chosen code, and the install and uninstall tests a temporary home
 * folder too, so they never reach a real systemd instance or touch the user's units, and they run
 * on every platform. The relative log file the refusal test passes lies under the build folder, so
 * that a fault letting it through, as a mutation test plants, leaves no file in the source tree.
 */
class SystemdSchedulerServiceTest : FunSpec({

	val service = SystemdSchedulerService()
	val exe = "/opt/omnisign/bin/omnisign"

	/** Whether the tests run on Windows. */
	fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")

	/** Whether the tests run on a POSIX system as a user that file permissions apply to. */
	fun isPosixNonRoot() = !isWindows() && System.getProperty("user.name") != "root"

	/**
	 * Write a stand-in for `systemctl` into [dir] that appends the arguments of each call to [calls],
	 * one call a line, and exits with [exitCode]: a batch file on Windows, a shell script elsewhere.
	 *
	 * @return The command to run as `systemctl`.
	 */
	fun fakeSystemctl(dir: Path, calls: Path, exitCode: Int): String {
		dir.createDirectories()
		return if (isWindows()) {
			val script = dir / "systemctl.cmd"
			script.writeText("@echo %*>>\"$calls\"\r\n@exit /b $exitCode\r\n")
			script.toString()
		} else {
			val script = dir / "systemctl"
			script.writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '$calls'\nexit $exitCode\n")
			script.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
			script.toString()
		}
	}

	/** The calls the stand-in recorded in [calls], or none when it never ran. */
	fun recorded(calls: Path): List<String> =
		if (calls.exists()) calls.readLines().map { it.trim() } else emptyList()

	/**
	 * A service working under the temporary [home], with a stand-in `systemctl` that records its
	 * calls in `calls.txt` there and exits with [exitCode].
	 */
	fun serviceIn(home: Path, exitCode: Int = 0) =
		SystemdSchedulerService(userHome = home, systemctl = fakeSystemctl(home / "bin", home / "calls.txt", exitCode))

	/** The log file the service offers in its messages for a user whose home is [home]. */
	fun suggestedLog(home: Path): String = (home / ".local" / "state" / "omnisign" / "renew.log").toString()

	test("renderTimerUnit emits a daily OnCalendar trigger with Persistent catch-up") {
		val unit = service.renderTimerUnit(3, 30)
		unit shouldContain "OnCalendar=*-*-* 03:30:00"
		unit shouldContain "Persistent=true"
		unit shouldContain "WantedBy=timers.target"
	}

	test("renderServiceUnit runs renew as a oneshot service, with the executable's path quoted") {
		val unit = service.renderServiceUnit("/usr/bin/omnisign", null)
		unit shouldContain "Type=oneshot"
		unit shouldContain "ExecStart=\"/usr/bin/omnisign\" renew"
		unit shouldNotContain "StandardOutput"
	}

	test("renderServiceUnit appends output to the log file when provided") {
		val unit = service.renderServiceUnit("/usr/bin/omnisign", "/var/log/omnisign.log")
		unit shouldContain "StandardOutput=append:/var/log/omnisign.log"
		unit shouldContain "StandardError=append:/var/log/omnisign.log"
	}

	test("renderServiceUnit quotes an executable path containing spaces") {
		val unit = service.renderServiceUnit("/opt/My Apps/omnisign", null)
		unit shouldContain "ExecStart=\"/opt/My Apps/omnisign\" renew"
	}

	test("renderServiceUnit doubles a percent sign in both paths, which systemd would take for a specifier, and leaves a dollar sign") {
		val unit = service.renderServiceUnit("/home/jan/apps/omnisign-100%/bin/omnisign", "/home/jan/logs/100% \$HOME.log")
		unit shouldContain "ExecStart=\"/home/jan/apps/omnisign-100%%/bin/omnisign\" renew"
		unit shouldContain "StandardOutput=append:/home/jan/logs/100%% \$HOME.log"
		unit shouldContain "StandardError=append:/home/jan/logs/100%% \$HOME.log"
	}

	test("renderServiceUnit refuses an executable path systemd cannot run, and takes one with letters outside ASCII") {
		listOf("/opt/omni\"sign", "/opt/jan's/omnisign", "/opt/omni\\sign", "/opt/omni\nsign", "/opt/omni\u007Fsign")
			.forEach { path ->
				shouldThrow<IllegalArgumentException> { service.renderServiceUnit(path, null) }
					.message shouldContain "systemd cannot run OmniSign from a path"
			}
		service.renderServiceUnit("/home/jiří/bin/omnisign", null) shouldContain "ExecStart=\"/home/jiří/bin/omnisign\" renew"
	}

	test("noSystemdMessage shows the manual command and an example crontab line") {
		val message = service.noSystemdMessage("/usr/bin/omnisign", 2, 0, "/var/log/renew.log")
		message shouldContain "\t/usr/bin/omnisign renew\n"
		message shouldContain "0 2 * * * /usr/bin/omnisign renew >> /var/log/renew.log 2>&1"
	}

	test("noSystemdMessage quotes the manual command's path for the shell") {
		service.noSystemdMessage("/opt/My Apps/omnisign", 2, 0, null) shouldContain "\t'/opt/My Apps/omnisign' renew\n"
	}

	test("manualCronLine quotes each path for the shell and takes a percent sign out of the quotes, escaped for cron") {
		service.manualCronLine("/opt/My Apps/jan's omnisign", 3, 30, "/home/jan/logs/100% \$HOME.log") shouldBe
			"30 3 * * * '/opt/My Apps/jan'\\''s omnisign' renew >> '/home/jan/logs/100'\\%' \$HOME.log' 2>&1"
	}

	test("manualCronLine leaves a path of ASCII letters, digits and /._-+:@, unquoted, the first and last of each range included") {
		service.manualCronLine("/opt/aAzZ09/._-+:@,/omnisign", 2, 0, null) shouldBe
			"0 2 * * * /opt/aAzZ09/._-+:@,/omnisign renew"
	}

	test("manualCronLine quotes a path with a character next to those it leaves unquoted") {
		"`{[?*;".forEach { char ->
			service.manualCronLine("/opt/omni${char}sign", 2, 0, null) shouldBe "0 2 * * * '/opt/omni${char}sign' renew"
		}
	}

	test("manualCronLine writes an empty path as an empty quoted word, which the shell still reads as a word") {
		service.manualCronLine("", 2, 0, null) shouldBe "0 2 * * * '' renew"
	}

	test("install registers the timer, appending to the log file normalized, with its missing folders created") {
		val home = tempdir().toPath()
		val systemd = serviceIn(home)

		systemd.install(exe, 3, 30, "$home/state/stale-segment/../omnisign/renew.log")

		val logFile = home / "state" / "omnisign" / "renew.log"
		logFile.exists().shouldBeTrue()
		(home / "state" / "stale-segment").exists().shouldBeFalse()
		val unitDir = home / ".config" / "systemd" / "user"
		val serviceUnit = (unitDir / "omnisign-renewal.service").readText()
		serviceUnit shouldContain "ExecStart=\"$exe\" renew"
		serviceUnit shouldContain "StandardOutput=append:$logFile\n"
		serviceUnit shouldContain "StandardError=append:$logFile\n"
		(unitDir / "omnisign-renewal.timer").readText() shouldContain "OnCalendar=*-*-* 03:30:00"
		recorded(home / "calls.txt") shouldContainExactly
			listOf("--user show-environment", "--user daemon-reload", "--user enable --now omnisign-renewal.timer")
		systemd.isInstalled().shouldBeTrue()
	}

	test("install keeps what an existing log file holds") {
		val home = tempdir().toPath()
		val logFile = (home / "renew.log").apply { writeText("previous run\n") }

		serviceIn(home).install(exe, 2, 0, logFile.toString())

		logFile.readText() shouldBe "previous run\n"
	}

	test("install takes a log file whose path holds a space, which stays on its unit file line") {
		val home = tempdir().toPath()
		val logFile = home / "renewal logs" / "renew.log"

		serviceIn(home).install(exe, 2, 0, logFile.toString())

		logFile.exists().shouldBeTrue()
		(home / ".config" / "systemd" / "user" / "omnisign-renewal.service").readText() shouldContain
			"StandardOutput=append:$logFile\n"
	}

	test("uninstall disables the timer and removes both units from the home folder it was given") {
		val home = tempdir().toPath()
		val systemd = serviceIn(home)
		systemd.install(exe, 2, 0, null)

		systemd.uninstall()

		systemd.isInstalled().shouldBeFalse()
		(home / ".config" / "systemd" / "user").listDirectoryEntries().shouldBeEmpty()
		recorded(home / "calls.txt").takeLast(2) shouldContainExactly
			listOf("--user disable --now omnisign-renewal.timer", "--user daemon-reload")
	}

	test("install refuses a relative log file before it reaches systemd or writes anything") {
		val home = tempdir().toPath()

		val error = shouldThrow<IllegalArgumentException> {
			serviceIn(home).install(exe, 2, 0, "build/relative-renewal-log/renew.log")
		}

		error.message shouldContain "must be an absolute path"
		error.message shouldContain suggestedLog(home)
		recorded(home / "calls.txt").shouldBeEmpty()
		home.listDirectoryEntries().map { it.name } shouldContainExactly listOf("bin")
	}

	test("install refuses a log file path a unit file line cannot hold, before it creates the file") {
		val home = tempdir().toPath()
		val systemd = serviceIn(home)

		listOf("$home/renew\n.log", "$home/renew\u007F.log", "$home/renew.log ", "$home/renew.log\\").forEach { path ->
			shouldThrow<IllegalArgumentException> { systemd.install(exe, 2, 0, path) }
				.message shouldContain "A systemd unit cannot hold a renewal log file path"
		}

		recorded(home / "calls.txt").shouldBeEmpty()
		home.listDirectoryEntries().map { it.name } shouldContainExactly listOf("bin")
	}

	test("install refuses a log file whose folder is a file, before it reaches systemd") {
		val home = tempdir().toPath()
		val blocker = (home / "logs").apply { writeText("not a folder") }

		val error = shouldThrow<IllegalArgumentException> { serviceIn(home).install(exe, 2, 0, "$blocker/renew.log") }

		error.message shouldContain "cannot be written to ($blocker is not a folder)"
		error.message shouldContain suggestedLog(home)
		recorded(home / "calls.txt").shouldBeEmpty()
		(home / ".config").exists().shouldBeFalse()
	}

	test("install refuses a log file below a file further up its path, naming the folder it could not create") {
		val home = tempdir().toPath()
		val blocker = (home / "notes.txt").apply { writeText("not a folder") }

		val error = shouldThrow<IllegalArgumentException> {
			serviceIn(home).install(exe, 2, 0, "$blocker/logs/renew.log")
		}

		error.message shouldContain "cannot be written to (${blocker / "logs"}"
		error.message shouldContain suggestedLog(home)
		recorded(home / "calls.txt").shouldBeEmpty()
	}

	test("install refuses a log file path that names a folder") {
		val home = tempdir().toPath()
		val folder = (home / "logs").createDirectories()

		val error = shouldThrow<IllegalArgumentException> { serviceIn(home).install(exe, 2, 0, folder.toString()) }

		error.message shouldContain "The renewal log file $folder cannot be written to"
		recorded(home / "calls.txt").shouldBeEmpty()
	}

	test("install refuses a log file the user may not write to, leaving what it holds").config(
		enabledIf = { isWindows() || isPosixNonRoot() },
	) {
		val home = tempdir().toPath()
		val logFile = (home / "renew.log").apply { writeText("previous run\n") }
		logFile.toFile().setWritable(false)
		try {
			val error = shouldThrow<IllegalArgumentException> { serviceIn(home).install(exe, 2, 0, logFile.toString()) }

			error.message shouldContain "cannot be written to (permission denied)"
			logFile.readText() shouldBe "previous run\n"
			recorded(home / "calls.txt").shouldBeEmpty()
		} finally {
			logFile.toFile().setWritable(true)
		}
	}

	test("install refuses a log file in a folder the user may not write to, as /var/log is for most users").config(
		enabledIf = { isPosixNonRoot() },
	) {
		val home = tempdir().toPath()
		val folder = (home / "log").createDirectories()
		folder.setPosixFilePermissions(PosixFilePermissions.fromString("r-xr-xr-x"))
		try {
			val error = shouldThrow<IllegalArgumentException> {
				serviceIn(home).install(exe, 2, 0, "$folder/omnisign-renew.log")
			}

			error.message shouldContain "cannot be written to (permission denied)"
			recorded(home / "calls.txt").shouldBeEmpty()
		} finally {
			folder.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
		}
	}

	test("install without a systemd user instance gives a crontab line with the log file checked and writes no unit") {
		val home = tempdir().toPath()

		val error = shouldThrow<IllegalStateException> {
			serviceIn(home, exitCode = 1).install(exe, 2, 0, "$home/state/stale-segment/../renew.log")
		}

		val logFile = home / "state" / "renew.log"
		error.message shouldContain "0 2 * * * $exe renew >> "
		error.message shouldContain logFile.toString()
		error.message shouldNotContain "stale-segment"
		logFile.exists().shouldBeTrue()
		recorded(home / "calls.txt") shouldContainExactly listOf("--user show-environment")
		(home / ".config").exists().shouldBeFalse()
	}

	test("install refuses an executable path systemd cannot run before it writes a unit") {
		val home = tempdir().toPath()

		shouldThrow<IllegalArgumentException> { serviceIn(home).install("/opt/jan's apps/omnisign", 2, 0, null) }
			.message shouldContain "systemd cannot run OmniSign from a path"

		recorded(home / "calls.txt") shouldContainExactly listOf("--user show-environment")
		(home / ".config").exists().shouldBeFalse()
	}

	test("install without a systemd user instance gives the crontab line for an executable path systemd could not run") {
		val home = tempdir().toPath()

		val error = shouldThrow<IllegalStateException> {
			serviceIn(home, exitCode = 1).install("/opt/jan's apps/omnisign", 2, 0, null)
		}

		error.message shouldContain "0 2 * * * '/opt/jan'\\''s apps/omnisign' renew"
	}

	test("isAvailable is true when systemctl --user show-environment succeeds") {
		val dir = tempdir().toPath()
		val calls = dir / "calls.txt"

		SystemdSchedulerService(systemctl = fakeSystemctl(dir / "bin", calls, 0)).isAvailable().shouldBeTrue()
		recorded(calls) shouldContainExactly listOf("--user show-environment")
	}

	test("isAvailable is false when systemctl --user show-environment fails, as without a user instance") {
		val dir = tempdir().toPath()
		val calls = dir / "calls.txt"

		SystemdSchedulerService(systemctl = fakeSystemctl(dir / "bin", calls, 1)).isAvailable().shouldBeFalse()
		recorded(calls) shouldContainExactly listOf("--user show-environment")
	}

	test("isAvailable is false when systemctl cannot be started, as where systemd is not installed") {
		val missing = tempdir().toPath() / "no-systemctl"

		SystemdSchedulerService(systemctl = missing.toString()).isAvailable().shouldBeFalse()
	}
})
