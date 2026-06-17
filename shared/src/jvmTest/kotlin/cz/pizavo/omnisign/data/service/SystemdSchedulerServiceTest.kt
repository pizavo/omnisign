package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies the unit-file rendering and the no-systemd guidance of [SystemdSchedulerService].
 *
 * The rendering and message helpers are pure, so these assertions run on every platform; the
 * `systemctl`-backed install/uninstall behaviour is exercised only on a real systemd host and
 * is therefore not covered here.
 */
class SystemdSchedulerServiceTest : FunSpec({

	val service = SystemdSchedulerService()

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
})
