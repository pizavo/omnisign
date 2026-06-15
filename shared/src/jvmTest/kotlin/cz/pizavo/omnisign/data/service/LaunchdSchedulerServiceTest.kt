package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies the plist rendering of [LaunchdSchedulerService].
 *
 * `renderPlist` is pure, so these assertions run on every platform; the `launchctl`-backed
 * install/uninstall behaviour is exercised only on a real macOS host and is not covered here.
 */
class LaunchdSchedulerServiceTest : FunSpec({

	val service = LaunchdSchedulerService()

	test("renderPlist sets the label, program arguments and calendar interval") {
		val plist = service.renderPlist("/usr/local/bin/omnisign", 2, 15, null)
		plist shouldContain "<string>cz.pizavo.omnisign.renewal</string>"
		plist shouldContain "<string>/usr/local/bin/omnisign</string>"
		plist shouldContain "<string>renew</string>"
		plist shouldContain "<key>Hour</key>"
		plist shouldContain "<integer>2</integer>"
		plist shouldContain "<key>Minute</key>"
		plist shouldContain "<integer>15</integer>"
		plist shouldNotContain "StandardOutPath"
	}

	test("renderPlist redirects output to the log file when provided") {
		val plist = service.renderPlist("/usr/local/bin/omnisign", 2, 0, "/var/log/omnisign.log")
		plist shouldContain "<key>StandardOutPath</key>"
		plist shouldContain "<string>/var/log/omnisign.log</string>"
		plist shouldContain "<key>StandardErrorPath</key>"
	}

	test("renderPlist escapes XML metacharacters in paths") {
		val plist = service.renderPlist("/opt/a&b/omnisign", 2, 0, null)
		plist shouldContain "<string>/opt/a&amp;b/omnisign</string>"
	}
})
