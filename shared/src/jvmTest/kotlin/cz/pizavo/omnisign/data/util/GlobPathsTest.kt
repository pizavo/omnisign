package cz.pizavo.omnisign.data.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.FileSystems
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.div
import kotlin.io.path.exists

/**
 * Verifies [isAbsoluteGlobRoot] accepts absolute-rooted globs and rejects relative ones, that
 * [absolutizeGlob] resolves a relative glob against a base directory while leaving the wildcard tail
 * intact, and that [renewalGlobMatcher] and [globSelects] let a recursive segment match no
 * directory, as the renewal run and the desktop's renewal offer both rely on. Uses the current
 * platform's notion of "absolute" and its case rules (via the JDK), and asks the file system first
 * whether it ignores letter case and whether it may create symbolic links, so the assertions hold
 * on any OS; the test that needs a link is skipped where none can be made.
 */
class GlobPathsTest : FunSpec({

	val absoluteRoot = Paths.get("").toAbsolutePath().toString()
	val base = tempdir().toPath()
	val baseSlash = base.toString().replace('\\', '/')
	val fileSystem = FileSystems.getDefault()
	val isWindows = System.getProperty("os.name", "").startsWith("Windows")
	val ignoresCase = run {
		(base / "CaseProbe").createDirectories()
		(base / "caseprobe").exists()
	}
	val canLink = runCatching {
		(base / "link-probe").createSymbolicLinkPointingTo((base / "link-probe-target").createDirectories())
	}.isSuccess

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

	test("accepts glob patterns the platform can parse") {
		isParseableGlob("$absoluteRoot/**/*.pdf").shouldBeTrue()
		isParseableGlob("$absoluteRoot/doc-?.pdf").shouldBeTrue()
		isParseableGlob("$absoluteRoot/doc.pdf").shouldBeTrue()
	}

	test("rejects a pattern whose brackets or braces are left open") {
		isParseableGlob("$absoluteRoot/[.pdf").shouldBeFalse()
		isParseableGlob("$absoluteRoot/{a,b.pdf").shouldBeFalse()
	}

	test("rejects an unparseable pattern the absolute-root check accepts") {
		val glob = "$absoluteRoot/[.pdf"
		isAbsoluteGlobRoot(glob).shouldBeTrue()
		isParseableGlob(glob).shouldBeFalse()
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

	test("splits a wildcard glob into the directory it is rooted in and its pattern") {
		globRootAndTail("/docs/**/*.pdf") shouldBe ("/docs" to "**/*.pdf")
		globRootAndTail("C:\\Docs\\sub\\report-*.pdf") shouldBe ("C:/Docs/sub" to "report-*.pdf")
		globRootAndTail("/docs/a?.pdf") shouldBe ("/docs" to "a?.pdf")
		globRootAndTail("/docs/[ab].pdf") shouldBe ("/docs" to "[ab].pdf")
		globRootAndTail("/docs/{a,b}.pdf") shouldBe ("/docs" to "{a,b}.pdf")
	}

	test("roots a glob without a directory part in the working directory") {
		globRootAndTail("*.pdf") shouldBe ("." to "*.pdf")
	}

	test("does not split a literal path") {
		globRootAndTail("/docs/report.pdf").shouldBeNull()
	}

	test("lets a leading recursive segment match no directory at all") {
		val matcher = renewalGlobMatcher(fileSystem, "**/*.pdf")
		matcher.matches(Path("top.pdf")).shouldBeTrue()
		matcher.matches(Path("a/mid.pdf")).shouldBeTrue()
		matcher.matches(Path("a/b/deep.pdf")).shouldBeTrue()
	}

	test("keeps a single star within one directory") {
		val matcher = renewalGlobMatcher(fileSystem, "*.pdf")
		matcher.matches(Path("top.pdf")).shouldBeTrue()
		matcher.matches(Path("a/mid.pdf")).shouldBeFalse()
	}

	test("lets a recursive segment after a directory match no directory") {
		val matcher = renewalGlobMatcher(fileSystem, "a/**/b/*.pdf")
		matcher.matches(Path("a/b/direct.pdf")).shouldBeTrue()
		matcher.matches(Path("a/x/y/b/below.pdf")).shouldBeTrue()
		matcher.matches(Path("a/outside.pdf")).shouldBeFalse()
	}

	test("lets a recursive segment after a closed group or class match no directory") {
		renewalGlobMatcher(fileSystem, "{a,c}/**/x.pdf").matches(Path("a/x.pdf")).shouldBeTrue()
		renewalGlobMatcher(fileSystem, "[ac]/**/x.pdf").matches(Path("c/x.pdf")).shouldBeTrue()
	}

	test("reads a brace inside a class as a plain character") {
		renewalGlobMatcher(fileSystem, "[{]/**/x.pdf").matches(Path("{/x.pdf")).shouldBeTrue()
		renewalGlobMatcher(fileSystem, "[a{]/**/x.pdf").matches(Path("{/x.pdf")).shouldBeTrue()
	}

	test("leaves a recursive segment inside a group as the platform reads it") {
		val matcher = renewalGlobMatcher(fileSystem, "{a/**/*.pdf,b.pdf}")
		matcher.matches(Path("a/x/y.pdf")).shouldBeTrue()
		matcher.matches(Path("b.pdf")).shouldBeTrue()
		matcher.matches(Path("a/y.pdf")).shouldBeFalse()
	}

	test("leaves a double star that does not start a segment as the platform reads it") {
		val matcher = renewalGlobMatcher(fileSystem, "x**/*.pdf")
		matcher.matches(Path("xa/b.pdf")).shouldBeTrue()
		matcher.matches(Path("x.pdf")).shouldBeFalse()
	}

	test("selects the PDFs directly in the directory of a recursive glob as well as those below it") {
		globSelects("$baseSlash/**/*.pdf", "$baseSlash/top.pdf").shouldBeTrue()
		globSelects("$baseSlash/**/*.pdf", "$baseSlash/a/b/deep.pdf").shouldBeTrue()
	}

	test("reads backslashes in a wildcard glob as separators") {
		val glob = base.toString().replace('/', '\\') + "\\**\\*.pdf"
		globSelects(glob, "$base/a/report.pdf").shouldBeTrue()
	}

	test("does not select a file outside the glob's directory") {
		globSelects("$baseSlash/sub/**/*.pdf", "$baseSlash/other/report.pdf").shouldBeFalse()
	}

	test("does not select a path that leaves the glob's directory through a parent reference") {
		globSelects("$baseSlash/sub/**/*.pdf", "$baseSlash/sub/../outside.pdf").shouldBeFalse()
	}

	test("resolves parent references in the glob's directory") {
		globSelects("$baseSlash/sub/../*.pdf", "$baseSlash/report.pdf").shouldBeTrue()
	}

	test("selects only PDFs, whatever the case of their extension") {
		globSelects("$baseSlash/*", "$baseSlash/SCAN.PDF").shouldBeTrue()
		globSelects("$baseSlash/*", "$baseSlash/notes.txt").shouldBeFalse()
	}

	test("selects the file a literal glob names and no other") {
		globSelects("$baseSlash/report.pdf", "$baseSlash/report.pdf").shouldBeTrue()
		globSelects("$baseSlash/report.pdf", "$baseSlash/sub/../report.pdf").shouldBeTrue()
		globSelects("$baseSlash/sub/../report.pdf", "$baseSlash/report.pdf").shouldBeTrue()
		globSelects("$baseSlash/report.pdf", "$baseSlash/other.pdf").shouldBeFalse()
		globSelects("$baseSlash/a/report.pdf", "$baseSlash/b/report.pdf").shouldBeFalse()
	}

	test("finds the directory a glob is rooted in, and a literal glob's file, as the file system does") {
		val file = ((base / "CaseFolder").createDirectories() / "report.pdf").createFile()
		globSelects("$baseSlash/casefolder/*.pdf", file.toString()) shouldBe ignoresCase
		globSelects("$baseSlash/casefolder/report.pdf", file.toString()) shouldBe ignoresCase
		globSelects("$baseSlash/CaseFolder/REPORT.pdf", file.toString()) shouldBe ignoresCase
	}

	test("compares the path the matcher reads under the platform's letter case rules") {
		globSelects("$baseSlash/docs/report-*.pdf", "$baseSlash/docs/REPORT-1.pdf") shouldBe isWindows
	}

	test("reads the names below a glob's directory as the file system stores them").config(enabledIf = { ignoresCase }) {
		((base / "Stored").createDirectories() / "REPORT-1.pdf").createFile()
		globSelects("$baseSlash/Stored/report-*.pdf", "$baseSlash/Stored/report-1.pdf") shouldBe isWindows
	}

	test("never takes a symbolic link named like the file in another letter case for that file")
		.config(enabledIf = { canLink && !ignoresCase }) {
			val folder = (base / "with-case-link").createDirectories()
			val file = (folder / "a.pdf").createFile()
			val link = (folder / "A.pdf").createSymbolicLinkPointingTo(file)
			globSelects(link.toString().replace('\\', '/'), file.toString()).shouldBeFalse()
		}

	test("selects nothing through a pattern or a path the platform cannot parse") {
		globSelects("$baseSlash/[.pdf", "$baseSlash/[.pdf").shouldBeFalse()
		globSelects("$baseSlash/*.pdf", "$baseSlash/a\u0000.pdf").shouldBeFalse()
	}
})
