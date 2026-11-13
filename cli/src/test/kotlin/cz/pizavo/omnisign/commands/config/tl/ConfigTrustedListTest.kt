package cz.pizavo.omnisign.commands.config.tl

import arrow.core.left
import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempfile
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module

/**
 * Behavioral tests for how `config tl add`, `list` and `remove` report a failure, and for the
 * warnings `add` gives about a list it registers.
 *
 * Each failure is explained on stderr with exit status 1, so a script never takes a trusted list it
 * failed to register for one that validation will use. A list registered without a signing
 * certificate is not verified, which `add` says, and it says too when such a list is fetched over
 * plain HTTP, where anyone on the network path can change it.
 */
class ConfigTrustedListTest : FunSpec({

	val manageTl: ManageTrustedListsUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageTl } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageTl) }

	test("add names a profile that does not exist and exits non-zero") {
		coEvery { manageTl.addTrustedList(any(), "ghost") } returns ConfigurationError.noProfileNamed("ghost").left()

		val result = Omnisign().test(
			listOf(
				"config", "tl", "add",
				"--name", "internal",
				"--source", "https://tl.omnisign.test/tl.xml",
				"--profile", "ghost",
			),
		)

		result.statusCode shouldBe 1
		result.stderr shouldContain "No profile named 'ghost' found"
	}

	test("list names a profile that does not exist and exits non-zero") {
		coEvery { manageTl.listTrustedLists("ghost") } returns ConfigurationError.noProfileNamed("ghost").left()

		val result = Omnisign().test(listOf("config", "tl", "list", "--profile", "ghost"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No profile named 'ghost' found"
	}

	test("remove names a trusted list that is not registered and exits non-zero") {
		coEvery { manageTl.removeTrustedList("internal", null) } returns
			ConfigurationError.trustedListNotFound("internal").left()

		val result = Omnisign().test(listOf("config", "tl", "remove", "internal"))

		result.statusCode shouldBe 1
		result.stderr shouldContain "No trusted list named 'internal' found"
	}

	test("add warns that a list fetched over plain HTTP without a signing certificate can be changed on the way") {
		coEvery { manageTl.addTrustedList(any(), null) } returns Unit.right()

		val result = Omnisign().test(
			listOf("config", "tl", "add", "--name", "internal", "--source", "http://tl.omnisign.test/tl.xml"),
		)

		result.statusCode shouldBe 0
		result.stdout shouldContain "TL signature will not be verified"
		result.stdout shouldContain "fetched over plain HTTP"
	}

	test("add gives no warning about plain HTTP when a signing certificate verifies the list") {
		coEvery { manageTl.addTrustedList(any(), null) } returns Unit.right()
		val signingCertificate = tempfile(suffix = ".pem")

		val result = Omnisign().test(
			listOf(
				"config", "tl", "add",
				"--name", "internal",
				"--source", "http://tl.omnisign.test/tl.xml",
				"--signing-cert", signingCertificate.path,
			),
		)

		result.statusCode shouldBe 0
		result.stdout shouldNotContain "will not be verified"
		result.stdout shouldNotContain "plain HTTP"
	}

	test("add warns only that the signature will not be verified for an HTTPS list without a signing certificate") {
		coEvery { manageTl.addTrustedList(any(), null) } returns Unit.right()

		val result = Omnisign().test(
			listOf("config", "tl", "add", "--name", "internal", "--source", "https://tl.omnisign.test/tl.xml"),
		)

		result.statusCode shouldBe 0
		result.stdout shouldContain "TL signature will not be verified"
		result.stdout shouldNotContain "plain HTTP"
	}
})
