package cz.pizavo.omnisign.commands.config.pkcs11

import arrow.core.left
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManagePkcs11LibrariesUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempfile
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module

/**
 * Behavioral tests for how the `config pkcs11` subcommands report a failure.
 *
 * A provisioning script registers the middleware before it signs with a token, so a library that
 * could not be registered, listed or removed has to end the command with status 1, and say why on
 * stderr, rather than pass for a success.
 */
class ConfigPkcs11Test : FunSpec({

	val managePkcs11: ManagePkcs11LibrariesUseCase = mockk()

	extension(
		KoinExtension(
			module { single { managePkcs11 } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(managePkcs11) }

	val library = tempfile(suffix = ".so")

	test("add reports a library it could not register and exits non-zero") {
		coEvery { managePkcs11.addLibrary(any()) } returns
			ConfigurationError.saveFailed(details = "disk full").left()

		val result = Omnisign().test(
			listOf("config", "pkcs11", "add", "--name", "safenet", "--path", library.absolutePath),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "Details: disk full"
	}

	test("list reports a failure and exits non-zero") {
		coEvery { managePkcs11.listLibraries() } returns
			ConfigurationError.loadFailed(details = "config file is not valid JSON").left()

		val result = Omnisign().test(listOf("config", "pkcs11", "list"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "Details: config file is not valid JSON"
	}

	test("remove names a library that is not registered and exits non-zero") {
		coEvery { managePkcs11.removeLibrary("safenet") } returns
			ConfigurationError.pkcs11LibraryNotFound("safenet").left()

		val result = Omnisign().test(listOf("config", "pkcs11", "remove", "safenet"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No PKCS#11 library named 'safenet' is registered"
	}
})
