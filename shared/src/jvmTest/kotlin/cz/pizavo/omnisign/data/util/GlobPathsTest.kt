package cz.pizavo.omnisign.data.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Paths

/**
 * Verifies [isAbsoluteGlobRoot] accepts absolute-rooted globs and rejects relative ones, and that
 * [absolutizeGlob] resolves a relative glob against a base directory while leaving the wildcard tail
 * intact. Uses the current platform's notion of "absolute" (via the JDK), so the assertions hold on
 * any OS.
 */
class GlobPathsTest : FunSpec({

	val absoluteRoot = Paths.get("").toAbsolutePath().toString()
	val base = tempdir().toPath()
	val baseSlash = base.toString().replace('\\', '/')

	test("accepts an absolute-rooted glob") {
		isAbsoluteGlobRoot("$absoluteRoot/**/*.pdf").shouldBeTrue()
	}

	test("accepts an absolute literal path with no wildcard") {
		isAbsoluteGlobRoot("$absoluteRoot/doc.pdf").shouldBeTrue()
	}

	test("rejects a relative glob") {
		isAbsoluteGlobRoot("docs/**/*.pdf").shouldBeFalse()
	}

	test("rejects a wildcard-only glob") {
		isAbsoluteGlobRoot("*.pdf").shouldBeFalse()
	}

	test("leaves an already-absolute glob unchanged") {
		absolutizeGlob("$baseSlash/sub/*.pdf", base) shouldBe "$baseSlash/sub/*.pdf"
	}

	test("resolves a leading-dot glob against the base") {
		absolutizeGlob("./*.pdf", base) shouldBe "$baseSlash/*.pdf"
	}

	test("resolves a wildcard-only glob against the base") {
		absolutizeGlob("*.pdf", base) shouldBe "$baseSlash/*.pdf"
	}

	test("resolves a recursive glob, keeping the tail") {
		absolutizeGlob("**/*.pdf", base) shouldBe "$baseSlash/**/*.pdf"
	}

	test("resolves a relative subdirectory glob") {
		absolutizeGlob("docs/*.pdf", base) shouldBe "$baseSlash/docs/*.pdf"
	}

	test("does not split a partial-segment wildcard into a directory") {
		absolutizeGlob("report-*.pdf", base) shouldBe "$baseSlash/report-*.pdf"
	}

	test("resolves a relative literal file with no wildcard") {
		absolutizeGlob("report.pdf", base) shouldBe "$baseSlash/report.pdf"
	}

	test("produces an absolute-rooted glob the add command will accept") {
		isAbsoluteGlobRoot(absolutizeGlob("./**/*.pdf", base)).shouldBeTrue()
	}
})
