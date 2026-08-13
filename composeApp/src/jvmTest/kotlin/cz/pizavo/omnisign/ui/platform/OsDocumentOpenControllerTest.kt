package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.PlatformFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Verifies [OsDocumentOpenController.fromArgs] only accepts a first argument that names an
 * existing regular file — so the internal `renew` / `probe` / `discover-modules` verbs and any
 * future verb are never mistaken for a document — and that the controller behaves as a
 * single-slot surface that [OsDocumentOpenController.consume] empties.
 */
class OsDocumentOpenControllerTest : FunSpec({

	val dir = tempdir()
	val pdf = File(dir, "contract.pdf").apply { writeText("%PDF-1.7") }

	test("resolves an existing file named on the command line") {
		val resolved = OsDocumentOpenController.fromArgs(arrayOf(pdf.path))

		resolved.shouldNotBeNull().file.absolutePath shouldBe pdf.absolutePath
	}

	test("absolutises a relative path so later saves resolve against the same file") {
		val resolved = OsDocumentOpenController.fromArgs(arrayOf(pdf.path))

		resolved.shouldNotBeNull().file.isAbsolute shouldBe true
	}

	test("ignores a launch with no arguments") {
		OsDocumentOpenController.fromArgs(emptyArray()).shouldBeNull()
	}

	test("ignores the internal verbs the desktop launcher also accepts") {
		listOf("renew", "probe", "discover-modules").forEach { verb ->
			OsDocumentOpenController.fromArgs(arrayOf(verb)).shouldBeNull()
		}
	}

	test("ignores a path that does not exist") {
		OsDocumentOpenController.fromArgs(arrayOf(File(dir, "missing.pdf").path)).shouldBeNull()
	}

	test("ignores a directory") {
		OsDocumentOpenController.fromArgs(arrayOf(dir.path)).shouldBeNull()
	}

	test("considers only the first argument") {
		OsDocumentOpenController.fromArgs(arrayOf("renew", pdf.path)).shouldBeNull()
	}

	test("exposes the startup file until it is consumed") {
		val controller = OsDocumentOpenController(PlatformFile(pdf))

		controller.request.value.shouldNotBeNull().file shouldBe pdf
		controller.consume()
		controller.request.value.shouldBeNull()
	}

	test("publishes a file offered after startup") {
		val controller = OsDocumentOpenController()

		controller.request.value.shouldBeNull()
		controller.offer(PlatformFile(pdf))
		controller.request.value.shouldNotBeNull().file shouldBe pdf
	}
})
