package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.GlobalConfig
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies the read-only contract of [ReadOnlyConfigRepository]: reads return the fixed
 * startup configuration and writes are rejected with a [ConfigurationError.SaveFailed].
 */
class ReadOnlyConfigRepositoryTest : FunSpec({

	val appConfig = AppConfig(
		global = GlobalConfig(),
		profiles = mapOf("fast" to ProfileConfig(name = "fast")),
	)
	val repo = ReadOnlyConfigRepository(appConfig)

	test("getCurrentConfig returns the startup configuration") {
		repo.getCurrentConfig() shouldBe appConfig
	}

	test("loadConfig returns the startup configuration") {
		repo.loadConfig().getOrNull() shouldBe appConfig
	}

	test("saveConfig is rejected with a read-only SaveFailed error") {
		val result = repo.saveConfig(AppConfig())
		result.isLeft() shouldBe true
		result.swap().getOrNull().shouldBeInstanceOf<ConfigurationError.SaveFailed>()
	}
})
