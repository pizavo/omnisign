package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.result.RenewalCheckCacheEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createFile
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.time.Instant

/**
 * Verifies [FileRenewalCheckCache] persistence, round-tripping, and pruning of stale entries.
 */
class FileRenewalCheckCacheTest : FunSpec({

	test("get returns null when no entry exists") {
		val cache = FileRenewalCheckCache(tempdir().toPath() / "cache.json")
		cache.get("/some/file.pdf").shouldBeNull()
	}

	test("put then get round-trips and persists across instances") {
		val dir = tempdir().toPath()
		val cacheFile = dir / "cache.json"
		val target = (dir / "doc.pdf").createFile()
		val entry = RenewalCheckCacheEntry(
			sizeBytes = 1234,
			lastModifiedMillis = 1_700_000_000_000,
			earliestRenewalAt = Instant.parse("2030-01-01T00:00:00Z"),
		)

		FileRenewalCheckCache(cacheFile).put(target.toString(), entry)

		FileRenewalCheckCache(cacheFile).get(target.toString()) shouldBe entry
	}

	test("remove drops the entry") {
		val dir = tempdir().toPath()
		val cacheFile = dir / "cache.json"
		val target = (dir / "doc.pdf").createFile()
		val cache = FileRenewalCheckCache(cacheFile)
		cache.put(target.toString(), RenewalCheckCacheEntry(1, 2, Instant.parse("2030-01-01T00:00:00Z")))

		cache.remove(target.toString())

		cache.get(target.toString()).shouldBeNull()
	}

	test("an entry written before profiles were recorded loads as one that records none") {
		val dir = tempdir().toPath()
		val cacheFile = dir / "cache.json"
		val target = (dir / "doc.pdf").createFile()
		cacheFile.writeText(
			"{${JsonPrimitive(target.toString())}:{\"sizeBytes\":1,\"lastModifiedMillis\":2," +
				"\"earliestRenewalAt\":\"2030-01-01T00:00:00Z\",\"terminal\":true}}",
		)

		val entry = FileRenewalCheckCache(cacheFile).get(target.toString()).shouldNotBeNull()

		entry.terminal shouldBe true
		entry.profileRecorded shouldBe false
	}

	test("prunes entries for files that no longer exist on load") {
		val dir = tempdir().toPath()
		val cacheFile = dir / "cache.json"
		val missing = dir / "deleted.pdf"
		FileRenewalCheckCache(cacheFile).put(
			missing.toString(),
			RenewalCheckCacheEntry(1, 2, Instant.parse("2030-01-01T00:00:00Z")),
		)

		FileRenewalCheckCache(cacheFile).get(missing.toString()).shouldBeNull()
	}
})
