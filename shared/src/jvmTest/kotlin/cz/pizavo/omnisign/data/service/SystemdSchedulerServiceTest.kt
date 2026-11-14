package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * Verifies the unit-file rendering, the no-systemd guidance and the systemd probe of
 * [SystemdSchedulerService].
 *
 * The rendering and message helpers are pure, so these assertions run on every platform. The
 * probe runs a stand-in for `systemctl` that records its arguments and exits with a chosen code, so
 * it never reaches a real systemd instance and runs on every platform too. The `systemctl`-backed
 * install/uninstall behaviour is exercised only on a real systemd host and is therefore not
 * covered here.
 */
class SystemdSchedulerServiceTest : FunSpec({

	val service = SystemdSchedulerService()

	/** Whether the tests run on Windows. */
	fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")

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

	test("renderTimerUnit emits a daily OnCalendar trigger with Persistent catch-up") {
		val unit = service.renderTimerUnit(3, 30)
		unit shouldContain "OnCalendar=*-*-* 03:30:00"
		unit shouldContain "Persistent=true"
		unit shouldContain "WantedBy=timers.target"
	}

	test("renderServiceUnit runs renew as a oneshot service") {
		val unit = service.renderServiceUnit("/usr/bin/omnisign", null)
		unit shouldContain "Type=oneshot"
		unit shouldContain "ExecStart=/usr/bin/omnisign renew"
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

	test("noSystemdMessage shows the manual command and an example crontab line") {
		val message = service.noSystemdMessage("/usr/bin/omnisign", 2, 0, "/var/log/renew.log")
		message shouldContain "/usr/bin/omnisign renew"
		message shouldContain "0 2 * * * /usr/bin/omnisign renew >> /var/log/renew.log 2>&1"
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
