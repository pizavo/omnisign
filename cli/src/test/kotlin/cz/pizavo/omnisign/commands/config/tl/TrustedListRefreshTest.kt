package cz.pizavo.omnisign.commands.config.tl

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
import cz.pizavo.omnisign.domain.port.TrustedListRefreshPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.module
import java.io.FileNotFoundException
import kotlin.time.Clock

/**
 * Behavioral tests for `config tl refresh`.
 *
 * A script runs the refresh to keep the shared cache current, so the exit status alone must tell it
 * whether every list was refreshed: only then does the command print ✅ and exit with status 0.
 * Otherwise it names the lists that could not be refreshed, or says why the refresh could not run,
 * on stderr and exits with status 1.
 */
class TrustedListRefreshTest : FunSpec({

	val port: TrustedListRefreshPort = mockk()
	val at = Clock.System.now()

	extension(
		KoinExtension(
			module { single { port } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest {
		clearMocks(port)
		every { port.lastRefreshAt } returns MutableStateFlow(at)
	}

	/** The non-empty lines the command printed on stderr. */
	fun CliktCommandTestResult.errorLines(): List<String> = stderr.lines().filter { it.isNotEmpty() }

	/** Run `config tl refresh` and return the result. */
	fun refresh(): CliktCommandTestResult = Omnisign().test(listOf("config", "tl", "refresh"))

	test("reports a refresh of every list and exits zero") {
		coEvery { port.refreshNow() } returns null

		val result = refresh()

		result.statusCode shouldBe 0
		result.stdout shouldContain "✅ Trusted lists refreshed at "
		result.stderr shouldBe ""
	}

	test("names a custom list that could not be refreshed and exits non-zero") {
		coEvery { port.refreshNow() } returns TrustedListRefreshFailure.CustomList("Company TL", at)

		val result = refresh()

		result.statusCode shouldBe 1
		result.errorLines() shouldBe listOf("❌ The trusted list 'Company TL' could not be refreshed.")
		result.stdout shouldNotContain "✅"
	}

	test("names the EU LOTL when it could not be refreshed") {
		coEvery { port.refreshNow() } returns TrustedListRefreshFailure.EuLotl(at)

		val result = refresh()

		result.statusCode shouldBe 1
		result.errorLines() shouldBe listOf("❌ The EU list of trusted lists (LOTL) could not be refreshed.")
	}

	test("names every custom list that could not be refreshed") {
		coEvery { port.refreshNow() } returns
			TrustedListRefreshFailure.Multiple(listOf("Company TL", "Partner TL"), at)

		val result = refresh()

		result.statusCode shouldBe 1
		result.errorLines() shouldBe listOf(
			"❌ 2 trusted lists could not be refreshed:",
			"  • Company TL",
			"  • Partner TL",
		)
	}

	test("names the EU LOTL first among the lists that could not be refreshed") {
		coEvery { port.refreshNow() } returns TrustedListRefreshFailure.EuLotlAndOthers(listOf("Company TL"), at)

		val result = refresh()

		result.statusCode shouldBe 1
		result.errorLines() shouldBe listOf(
			"❌ 2 trusted lists could not be refreshed:",
			"  • EU list of trusted lists (LOTL)",
			"  • Company TL",
		)
	}

	test("says why the refresh could not run and exits non-zero") {
		val reason = "C:\\certs\\signer.cer (The system cannot find the file specified)"
		coEvery { port.refreshNow() } throws FileNotFoundException(reason)

		val result = refresh()

		result.statusCode shouldBe 1
		result.errorLines() shouldBe listOf("❌ Could not refresh the trusted lists: $reason")
		result.stdout shouldNotContain "✅"
	}
})
