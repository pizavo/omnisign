package cz.pizavo.omnisign.packaging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.inputStream

/**
 * Verifies that the install scope dialog of the CLI's Windows installer, `src/main/jpackage/win/ui.wxf`,
 * puts the CLI in the folder that `windowsInstallSubdir` in the build script names.
 *
 * The dialog sets `INSTALLDIR` when the user picks a scope, which replaces the folder jpackage's
 * `--install-dir` chose. While it named `omnisign` instead of `omnisign-cli`, every interactive install
 * went into the desktop app's folder, and uninstalling either app removed the other. The `test` task
 * passes the folder name and the dialog's path as system properties.
 */
class WindowsInstallScopeDialogTest : FunSpec({

	/** Return the system property [name], which the `test` task in the build script sets. */
	fun testProperty(name: String): String =
		checkNotNull(System.getProperty(name)) { "$name is not set; run the test through the Gradle test task" }

	/**
	 * Return the condition and the value of each `Publish` element in the WiX fragment [ui] that sets
	 * `INSTALLDIR`.
	 */
	fun installDirPublishes(ui: Path): List<Pair<String, String>> {
		val wixNamespace = "http://wixtoolset.org/schemas/v4/wxs"
		val document = ui.inputStream().use {
			DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it)
		}
		val publishes = document.getElementsByTagNameNS(wixNamespace, "Publish")
		return (0 until publishes.length)
			.map { publishes.item(it) as Element }
			.filter { it.getAttribute("Property") == "INSTALLDIR" }
			.map { it.getAttribute("Condition") to it.getAttribute("Value") }
	}

	test("both install scopes put the CLI in the folder the build script gives jpackage") {
		val subdir = testProperty("omnisign.test.windowsInstallSubdir")
		val ui = Path(testProperty("omnisign.test.windowsInstallerUi"))

		installDirPublishes(ui) shouldContainExactlyInAnyOrder listOf(
			"INSTALL_SCOPE = \"perMachine\"" to "[ProgramFiles64Folder]$subdir",
			"INSTALL_SCOPE = \"perUser\"" to "[LocalAppDataFolder]$subdir",
		)
	}
})
