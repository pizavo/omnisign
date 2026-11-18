package cz.pizavo.omnisign.ui.platform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path

/**
 * Verifies where [desktopLogDirectory] puts the desktop application's logs on each operating system,
 * and that a blank or, on Linux, relative base directory from the environment is ignored, as the XDG
 * Base Directory specification requires, instead of putting the logs in `/omnisign`.
 */
class DesktopLogDirectoryTest : FunSpec({

	/** Return an environment that holds only the variable [name], set to [value]. */
	fun environmentWith(name: String, value: String): (String) -> String? = { if (it == name) value else null }

	test("on Linux the logs go to the XDG state directory when XDG_STATE_HOME names an absolute path") {
		desktopLogDirectory("Linux", "/home/alice", environmentWith("XDG_STATE_HOME", "/var/lib/alice/state")) shouldBe
			Path("/var/lib/alice/state", "omnisign")
	}

	test("on Linux the logs go to ~/.local/state when XDG_STATE_HOME is not set") {
		desktopLogDirectory("Linux", "/home/alice") { null } shouldBe Path("/home/alice/.local/state", "omnisign")
	}

	test("on Linux an empty or blank XDG_STATE_HOME counts as unset, so the logs do not go to /omnisign") {
		desktopLogDirectory("Linux", "/home/alice", environmentWith("XDG_STATE_HOME", "")) shouldBe
			Path("/home/alice/.local/state", "omnisign")
		desktopLogDirectory("Linux", "/home/alice", environmentWith("XDG_STATE_HOME", "  ")) shouldBe
			Path("/home/alice/.local/state", "omnisign")
	}

	test("on Linux a relative XDG_STATE_HOME is ignored, as the XDG Base Directory specification requires") {
		desktopLogDirectory("Linux", "/home/alice", environmentWith("XDG_STATE_HOME", "state")) shouldBe
			Path("/home/alice/.local/state", "omnisign")
	}

	test("on Windows the logs go under LOCALAPPDATA, and under AppData/Local in the home directory when it is blank or unset") {
		val home = "C:\\Users\\alice"
		desktopLogDirectory("Windows 11", home, environmentWith("LOCALAPPDATA", "D:\\Profiles\\alice\\Local")) shouldBe
			Path("D:\\Profiles\\alice\\Local", "omnisign", "logs")
		desktopLogDirectory("Windows 11", home, environmentWith("LOCALAPPDATA", "")) shouldBe
			Path("$home/AppData/Local", "omnisign", "logs")
		desktopLogDirectory("Windows 11", home) { null } shouldBe Path("$home/AppData/Local", "omnisign", "logs")
	}

	test("on macOS the logs go to ~/Library/Logs, whatever the environment holds") {
		desktopLogDirectory("Mac OS X", "/Users/alice", environmentWith("XDG_STATE_HOME", "/var/state")) shouldBe
			Path("/Users/alice", "Library", "Logs", "omnisign")
	}
})
