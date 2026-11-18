package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.data.service.pkcs11DropDir
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path

/**
 * Verifies [appConfigDirectory] and [environmentDirectory]: an environment variable names a base
 * directory only while it holds an absolute path, so a blank or relative `APPDATA` falls back to the
 * roaming folder in the home directory instead of the root of the file system or the working
 * directory, and the PKCS#11 drop directory resolves through the same function.
 */
class AppConfigPathsTest : FunSpec({

	val home = tempdir().absolutePath
	val appData = tempdir().absolutePath

	/** An environment that sets only [name], to [value], or nothing for a `null` [value]. */
	fun environmentWith(name: String, value: String?): (String) -> String? = { if (it == name) value else null }

	test("Windows keeps the configuration in omnisign under an absolute APPDATA") {
		appConfigDirectory("Windows 11", home, environmentWith("APPDATA", appData)) shouldBe Path(appData, "omnisign")
	}

	test("Windows keeps the configuration in the home directory's roaming folder when APPDATA is unset, blank or relative") {
		listOf(null, "", "   ", "relative").forEach { value ->
			withClue("APPDATA=$value") {
				appConfigDirectory("Windows 11", home, environmentWith("APPDATA", value)) shouldBe
					Path(home, "AppData", "Roaming", "omnisign")
			}
		}
	}

	test("macOS and Linux keep the configuration under the home directory, whatever APPDATA holds") {
		appConfigDirectory("Mac OS X", home, environmentWith("APPDATA", appData)) shouldBe
			Path(home, "Library", "Application Support", "omnisign")
		appConfigDirectory("Linux", home, environmentWith("APPDATA", appData)) shouldBe Path(home, ".config", "omnisign")
	}

	test("environmentDirectory takes a variable's value only when it is an absolute path") {
		environmentDirectory("DIR", environmentWith("DIR", appData)) shouldBe Path(appData)
		listOf(null, "", "   ", "relative", "$appData\u0000").forEach { value ->
			withClue("DIR=$value") {
				environmentDirectory("DIR", environmentWith("DIR", value)).shouldBeNull()
			}
		}
	}

	test("the PKCS#11 drop directory is pkcs11 in the configuration directory") {
		pkcs11DropDir() shouldBe appConfigDirectory().resolve("pkcs11").toFile()
	}
})
