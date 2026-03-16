package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * Verifies the critical invariants of [DssValidationRepository] that protect against
 * two past regressions:
 *
 * 1. **OJ keystore missing** — the bundled `lotl-keystore.p12` classpath resource must be
 *    present, parseable as PKCS12, and contain at least one EU signing certificate.
 *
 * 2. **Ephemeral TL cache** — the TL cache directory must be a persistent, user-specific
 *    location (not the system temp directory) and the online-loader expiration must be
 *    strictly positive so DSS can reuse cached responses between validation calls.
 */
class DssValidationRepositoryTest : FunSpec({

	val repository = DssValidationRepository()
	val tmpDir = tempdir()

	fun tmpFile(name: String): File = File(tmpDir, name).also { it.createNewFile() }

	// ── OJ keystore regression ────────────────────────────────────────────────

	test("OJ keystore resource exists on the classpath") {
		val stream = DssValidationRepository::class.java
			.getResourceAsStream(DssValidationRepository.OJ_KEYSTORE_RESOURCE)
		stream shouldNotBe null
		stream!!.close()
	}

	test("OJ keystore is a valid PKCS12 openable with the expected password") {
		val stream = DssValidationRepository::class.java
			.getResourceAsStream(DssValidationRepository.OJ_KEYSTORE_RESOURCE)!!
		val source = KeyStoreCertificateSource(
			stream,
			DssValidationRepository.OJ_KEYSTORE_TYPE,
			DssValidationRepository.OJ_KEYSTORE_PASSWORD.toCharArray(),
		)
		source.certificates.shouldNotBeEmpty()
	}

	// ── TL cache regression ───────────────────────────────────────────────────

	test("TL cache expiration is strictly positive (not zero or negative)") {
		DssValidationRepository.TL_CACHE_EXPIRATION_MS shouldBeGreaterThan 0L
	}

	test("TL cache expiration is at least one hour") {
		val oneHourMs = 60 * 60 * 1000L
		DssValidationRepository.TL_CACHE_EXPIRATION_MS shouldBeGreaterThan oneHourMs
	}

	test("tlCacheDir is not the system temporary directory") {
		val cacheDir = repository.tlCacheDir()
		val sysTmp = System.getProperty("java.io.tmpdir")
		cacheDir.absolutePath shouldNotContain sysTmp
	}

	test("tlCacheDir path contains the omnisign application subdirectory") {
		val cacheDir = repository.tlCacheDir()
		cacheDir.absolutePath shouldContain "omnisign"
	}

	test("tlCacheDir path ends with the tl-cache subdirectory") {
		val cacheDir = repository.tlCacheDir()
		cacheDir.name shouldBe "tl-cache"
	}

	test("tlCacheDir is rooted inside the platform user-data directory") {
		val cacheDir = repository.tlCacheDir()
		val userHome = System.getProperty("user.home")
		val os = System.getProperty("os.name", "").lowercase()

		val expectedRoots = when {
			os.contains("win") -> listOf(
				System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local",
			)
			os.contains("mac") -> listOf("$userHome/Library/Caches")
			else -> listOf(
				System.getenv("XDG_CACHE_HOME") ?: "$userHome/.cache",
			)
		}.map { File(it).canonicalPath }

		val cacheDirCanonical = cacheDir.canonicalPath
		val isUnderExpectedRoot = expectedRoots.any { cacheDirCanonical.startsWith(it) }
		isUnderExpectedRoot shouldBe true
	}

	// ── validateDocument error handling ──────────────────────────────────────

	test("validateDocument returns InvalidDocument when the input file does not exist") {
		repository.validateDocument(
			ValidationParameters(inputFile = "/nonexistent/file.pdf")
		).shouldBeLeft().shouldBeInstanceOf<ValidationError.InvalidDocument>()
	}

	test("validateDocument returns ValidationFailed when the file is not a valid PDF") {
		val notAPdf = tmpFile("not-a-pdf.pdf").also { it.writeText("this is not a PDF") }

		repository.validateDocument(
			ValidationParameters(inputFile = notAPdf.absolutePath)
		).shouldBeLeft().shouldBeInstanceOf<ValidationError.ValidationFailed>()
	}
})

