package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Verifies [FileConfigRepository] file-based persistence: creation of defaults,
 * round-trip save/load, corrupt JSON handling, and cached config fallback.
 */
class FileConfigRepositoryTest : FunSpec({

	val tmpDir = tempdir()

	fun repoAt(name: String): FileConfigRepository {
		val path = Path.of(tmpDir.absolutePath, name, "config.json")
		return FileConfigRepository(configPath = path)
	}

	fun repoAtPath(path: Path): FileConfigRepository =
		FileConfigRepository(configPath = path)

	test("loadConfig creates default config when file does not exist") {
		val repo = repoAt("no-file")

		val config = repo.loadConfig().shouldBeRight()
		config shouldBe AppConfig(global = GlobalConfig())
	}

	test("loadConfig persists the default config to disk") {
		val path = Path.of(tmpDir.absolutePath, "persist-default", "config.json")
		val repo = repoAtPath(path)

		repo.loadConfig().shouldBeRight()
		path.toFile().exists() shouldBe true
	}

	test("round-trip save and load preserves config") {
		val repo = repoAt("round-trip")
		val config = AppConfig(
			global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512),
			profiles = mapOf("test" to ProfileConfig(name = "test")),
		)

		repo.saveConfig(config).shouldBeRight()
		val loaded = repo.loadConfig().shouldBeRight()

		loaded.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
		loaded.profiles.shouldContainKey("test")
	}

	test("loadConfig returns LoadFailed for corrupt JSON") {
		val path = Path.of(tmpDir.absolutePath, "corrupt", "config.json")
		path.parent.toFile().mkdirs()
		path.writeText("{{{invalid json")

		val repo = repoAtPath(path)
		repo.loadConfig().shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.LoadFailed>()
	}

	test("getCurrentConfig returns cached config after load") {
		val repo = repoAt("cached")
		val config = AppConfig(
			global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA384),
		)
		repo.saveConfig(config).shouldBeRight()
		repo.loadConfig().shouldBeRight()

		val current = repo.getCurrentConfig()
		current.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA384
	}

	test("getCurrentConfig falls back to default when load fails") {
		val path = Path.of(tmpDir.absolutePath, "fallback", "config.json")
		path.parent.toFile().mkdirs()
		path.writeText("not valid json")

		val repo = repoAtPath(path)
		val current = repo.getCurrentConfig()
		current shouldBe AppConfig(global = GlobalConfig())
	}

	test("saveConfig returns SaveFailed when path is not writable") {
		val readOnlyDir = tmpDir.resolve("readonly")
		readOnlyDir.mkdirs()
		val path = Path.of(readOnlyDir.absolutePath, "config.json")
		path.toFile().createNewFile()
		path.toFile().setReadOnly()

		val repo = repoAtPath(path)
		val result = repo.saveConfig(AppConfig(global = GlobalConfig()))
		result.shouldBeLeft().shouldBeInstanceOf<ConfigurationError.SaveFailed>()

		path.toFile().setWritable(true)
	}
})

