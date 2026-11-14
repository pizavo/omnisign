package cz.pizavo.omnisign.data.repository

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Verifies [FileConfigRepository] file-based persistence: creation of defaults, round-trip
 * save/load, the default fallback for a file that cannot be read, the refusal to save over such a
 * file, the atomic replacement of the file on a save, and the dispatcher the file access runs on.
 *
 * The symbolic-link test runs only where the test can create a link, which Windows allows only to an
 * administrator or in developer mode.
 */
class FileConfigRepositoryTest : FunSpec({

	val tmpDir = tempdir()

	fun repoAt(name: String): FileConfigRepository {
		val path = Path.of(tmpDir.absolutePath, name, "config.json")
		return FileConfigRepository(configPath = path)
	}

	fun repoAtPath(path: Path): FileConfigRepository =
		FileConfigRepository(configPath = path)

	fun fileWith(name: String, text: String): Path {
		val path = Path.of(tmpDir.absolutePath, name, "config.json")
		path.parent.toFile().mkdirs()
		path.writeText(text)
		return path
	}

	fun unreadable(path: Path): LocalizableText =
		LocalizableText.of(MessageKey.CONFIG_UNREADABLE_NOT_OVERWRITTEN, path.toAbsolutePath().toString())

	fun canCreateSymbolicLinks(): Boolean {
		val probe = createTempDirectory("symlink-probe")
		return try {
			probe.resolve("link").createSymbolicLinkPointingTo(probe.resolve("target"))
			true
		} catch (_: Exception) {
			false
		} finally {
			probe.toFile().deleteRecursively()
		}
	}

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

	test("loadConfig returns LoadFailed for corrupt JSON and leaves the file as it was") {
		val path = fileWith("corrupt", "{{{invalid json")

		val repo = repoAtPath(path)
		repo.loadConfig().shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.LoadFailed>()
		path.readText() shouldBe "{{{invalid json"
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
		val path = fileWith("fallback", "not valid json")

		val repo = repoAtPath(path)
		val current = repo.getCurrentConfig()
		current shouldBe AppConfig(global = GlobalConfig())
	}

	test("a change built on the defaults of a config written by a newer build leaves its profiles in the file") {
		val text = """{"global": {"defaultHashAlgorithm": "SHA3_1024"}, "profiles": {"work": {"name": "work"}}}"""
		val path = fileWith("newer-build", text)
		val repo = repoAtPath(path)

		val current = repo.getCurrentConfig()
		current shouldBe AppConfig(global = GlobalConfig())
		val changed = current.copy(global = current.global.copy(defaultHashAlgorithm = HashAlgorithm.SHA512))
		val error = repo.saveConfig(changed).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.SaveFailed>()
		error.text shouldBe unreadable(path)
		path.readText() shouldBe text
	}

	test("saveConfig refuses to replace a config file left invalid by a hand edit") {
		val text = """{"global": {"defaultHashAlgorithm": "SHA512",}}"""
		val path = fileWith("trailing-comma", text)

		val error = repoAtPath(path).saveConfig(AppConfig(global = GlobalConfig())).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.SaveFailed>()
		error.text shouldBe unreadable(path)
		path.readText() shouldBe text
	}

	test("setActiveProfile leaves a config file it cannot read as it was") {
		val path = fileWith("active-profile", "not valid json")

		val error = repoAtPath(path).setActiveProfile("work").shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.SaveFailed>()
		error.text shouldBe unreadable(path)
		path.readText() shouldBe "not valid json"
	}

	test("once the unreadable file is moved away, the next save starts over from the config it is given") {
		val path = fileWith("moved-away", "not valid json")
		val repo = repoAtPath(path)
		repo.saveConfig(AppConfig(global = GlobalConfig())).shouldBeLeft()

		path.moveTo(path.resolveSibling("config.json.broken"))
		repo.saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA384))).shouldBeRight()

		repoAtPath(path).loadConfig().shouldBeRight().global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA384
	}

	test("a save replaces the config file instead of rewriting it in place, so a cut-short save cannot truncate it") {
		val path = Path.of(tmpDir.absolutePath, "replace", "config.json")
		val repo = repoAtPath(path)
		repo.saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA384))).shouldBeRight()
		val before = path.readText()
		val previous = path.resolveSibling("previous.json")
		Files.createLink(previous, path)

		repo.saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))).shouldBeRight()

		previous.readText() shouldBe before
		repoAtPath(path).loadConfig().shouldBeRight().global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
	}

	test("a save through a symbolic link replaces the file it leads to and keeps the link").config(
		enabledIf = { canCreateSymbolicLinks() },
	) {
		val real = Path.of(tmpDir.absolutePath, "dotfiles", "omnisign.json")
		repoAtPath(real).saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA384)))
			.shouldBeRight()
		val link = Path.of(tmpDir.absolutePath, "linked", "config.json")
		link.parent.createDirectories()
		link.createSymbolicLinkPointingTo(real)

		repoAtPath(link).saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512)))
			.shouldBeRight()

		link.isSymbolicLink() shouldBe true
		repoAtPath(real).loadConfig().shouldBeRight().global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
		real.parent.listDirectoryEntries().map { it.name } shouldContainExactly listOf("omnisign.json")
	}

	test("a save follows a chain of relative symbolic links to the file at its end, which may not exist yet").config(
		enabledIf = { canCreateSymbolicLinks() },
	) {
		val root = Path.of(tmpDir.absolutePath, "chain")
		val real = root.resolve("store").resolve("omnisign.json")
		real.parent.createDirectories()
		val middle = root.resolve("middle.json")
		middle.createSymbolicLinkPointingTo(Path.of("store", "omnisign.json"))
		val link = root.resolve("app").resolve("config.json")
		link.parent.createDirectories()
		link.createSymbolicLinkPointingTo(Path.of("..", "middle.json"))

		repoAtPath(link).saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512)))
			.shouldBeRight()

		link.isSymbolicLink() shouldBe true
		middle.isSymbolicLink() shouldBe true
		repoAtPath(real).loadConfig().shouldBeRight().global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
	}

	test("a save leaves no temporary file beside the config file") {
		val path = Path.of(tmpDir.absolutePath, "no-temp", "config.json")
		val repo = repoAtPath(path)
		repo.loadConfig().shouldBeRight()

		repo.saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))).shouldBeRight()

		path.parent.listDirectoryEntries().map { it.name } shouldContainExactly listOf("config.json")
	}

	test("a config file that starts with a byte order mark loads, and a save replaces it") {
		val text = """{"global": {"defaultHashAlgorithm": "SHA512"}, "profiles": {"work": {"name": "work"}}}"""
		val path = fileWith("byte-order-mark", "\uFEFF$text")
		val repo = repoAtPath(path)

		repo.loadConfig().shouldBeRight().profiles.shouldContainKey("work")
		repo.setActiveProfile("work").shouldBeRight()

		val reloaded = repoAtPath(path).loadConfig().shouldBeRight()
		reloaded.activeProfile shouldBe "work"
		reloaded.global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
	}

	test("a save waits for another program to let go of the config file, which Windows does not atomically replace while it is open").config(
		enabledIf = { System.getProperty("os.name").lowercase().contains("win") },
	) {
		val path = Path.of(tmpDir.absolutePath, "held-open", "config.json")
		val repo = repoAtPath(path)
		repo.saveConfig(AppConfig(global = GlobalConfig())).shouldBeRight()
		val reader = Files.newInputStream(path)
		val probe = path.resolveSibling("probe.json")
		probe.writeText("{}")
		shouldThrow<FileSystemException> { probe.moveTo(path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
		probe.deleteIfExists()

		val save = async(Dispatchers.IO) {
			repo.saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512)))
		}
		delay(100)
		reader.close()

		save.await().shouldBeRight()
		repoAtPath(path).loadConfig().shouldBeRight().global.defaultHashAlgorithm shouldBe HashAlgorithm.SHA512
	}

	test("a save that cannot replace a config file another program keeps open fails, leaving it and no temporary file").config(
		enabledIf = { System.getProperty("os.name").lowercase().contains("win") },
	) {
		val path = Path.of(tmpDir.absolutePath, "held-for-good", "config.json")
		val repo = repoAtPath(path)
		repo.saveConfig(AppConfig(global = GlobalConfig())).shouldBeRight()
		val before = path.readText()

		val error = Files.newInputStream(path).use {
			repo.saveConfig(AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512))).shouldBeLeft()
		}

		error.shouldBeInstanceOf<ConfigurationError.SaveFailed>()
		error.text shouldBe LocalizableText.of(MessageKey.CONFIG_SAVE_FAILED)
		val cause = error.cause.shouldBeInstanceOf<FileSystemException>()
		error.details shouldBe cause::class.simpleName
		path.readText() shouldBe before
		path.parent.listDirectoryEntries().map { it.name } shouldContainExactly listOf("config.json")
	}

	test("saveConfig refuses to replace a read-only config file") {
		val path = Path.of(tmpDir.absolutePath, "readonly", "config.json")
		val repo = repoAtPath(path)
		repo.saveConfig(AppConfig(global = GlobalConfig())).shouldBeRight()
		val before = path.readText()
		path.toFile().setReadOnly()

		try {
			val error = repo.saveConfig(
				AppConfig(global = GlobalConfig(defaultHashAlgorithm = HashAlgorithm.SHA512)),
			).shouldBeLeft()

			error.shouldBeInstanceOf<ConfigurationError.SaveFailed>()
			error.text shouldBe LocalizableText.of(MessageKey.CONFIG_READ_ONLY, path.toAbsolutePath().toString())
			path.readText() shouldBe before
		} finally {
			path.toFile().setWritable(true)
		}
	}

	test("saveConfig returns SaveFailed when the config directory cannot be created") {
		val blocker = Path.of(tmpDir.absolutePath, "blocker")
		blocker.writeText("a file where the config directory should be")

		val error = repoAtPath(blocker.resolve("config.json")).saveConfig(AppConfig(global = GlobalConfig()))
			.shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.SaveFailed>()
		error.text shouldBe LocalizableText.of(MessageKey.CONFIG_SAVE_FAILED)
	}

	test("the file access runs on the dispatcher the repository was given") {
		val dispatches = AtomicInteger(0)
		val io = object : CoroutineDispatcher() {
			override fun dispatch(context: CoroutineContext, block: Runnable) {
				dispatches.incrementAndGet()
				Dispatchers.IO.dispatch(context, block)
			}
		}
		val repo = FileConfigRepository(Path.of(tmpDir.absolutePath, "dispatcher", "config.json"), io)

		repo.loadConfig().shouldBeRight()
		val afterLoad = dispatches.get()
		repo.saveConfig(AppConfig(global = GlobalConfig())).shouldBeRight()

		afterLoad shouldBeGreaterThan 0
		dispatches.get() shouldBeGreaterThan afterLoad
	}

	test("saveConfig sets POSIX owner-only permissions on non-Windows").config(
		enabledIf = { !System.getProperty("os.name").lowercase().contains("win") },
	) {
		val repo = repoAt("posix-perms")
		repo.saveConfig(AppConfig(global = GlobalConfig())).shouldBeRight()

		val path = Path.of(tmpDir.absolutePath, "posix-perms", "config.json")
		val perms = Files.getPosixFilePermissions(path)
		perms.toList().shouldContainExactlyInAnyOrder(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE,
		)
	}

	test("loadConfig sets POSIX owner-only permissions when creating default config on non-Windows").config(
		enabledIf = { !System.getProperty("os.name").lowercase().contains("win") },
	) {
		val path = Path.of(tmpDir.absolutePath, "posix-default", "config.json")
		val repo = repoAtPath(path)
		repo.loadConfig().shouldBeRight()

		val perms = Files.getPosixFilePermissions(path)
		perms.toList().shouldContainExactlyInAnyOrder(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE,
		)
	}
})
