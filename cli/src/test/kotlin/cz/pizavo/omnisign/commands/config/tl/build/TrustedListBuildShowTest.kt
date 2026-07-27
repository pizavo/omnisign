package cz.pizavo.omnisign.commands.config.tl.build

import arrow.core.right
import com.github.ajalt.clikt.testing.test
import cz.pizavo.omnisign.Omnisign
import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.model.config.TrustedListAddress
import cz.pizavo.omnisign.domain.usecase.ManageTrustedListsUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.koin.KoinExtension
import io.kotest.koin.KoinLifecycleMode
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.dsl.module

/**
 * Behavioral tests for `tl build show`, the only place a user can see how far a draft still is from
 * compiling.
 *
 * A draft is normally inspected mid-authoring, so the interesting cases are the incomplete ones: an
 * absent address has to read as a missing requirement rather than as a blank line, or the user is
 * left to discover it from the compiler.
 */
class TrustedListBuildShowTest : FunSpec({

	val manageTl: ManageTrustedListsUseCase = mockk()

	extension(
		KoinExtension(
			module { single { manageTl } },
			mode = KoinLifecycleMode.Test,
		),
	)

	beforeTest { clearMocks(manageTl) }

	/** Run `tl build show` against [draft] and return stdout. */
	suspend fun show(draft: CustomTrustedListDraft): String {
		coEvery { manageTl.getDraft(draft.name) } returns draft.right()
		return Omnisign().test(listOf("config", "tl", "build", "show", draft.name)).stdout
	}

	test("reports the scheme fields a complete draft carries") {
		val output = show(
			CustomTrustedListDraft(
				name = "internal",
				territory = "CZ",
				schemeOperatorName = "OmniSign Test Operator",
				schemeName = "Internal trust anchors",
				schemeInformationUri = "https://omnisign.test/tl",
				schemeOperatorAddress = TrustedListAddress(
					streetAddress = "Technicka 2",
					locality = "Praha",
					countryName = "CZ",
					postalCode = "16000",
					electronicAddress = "mailto:tl@omnisign.test",
				),
			),
		)

		output shouldContain "Scheme name    : Internal trust anchors"
		output shouldContain "Scheme info URI: https://omnisign.test/tl"
		output shouldContain "Technicka 2"
		output shouldContain "16000"
		output shouldContain "mailto:tl@omnisign.test"
		output shouldContain "History period : 65535 day(s)"
		output shouldContain "StatusDetn/appropriate"
	}

	test("spells out that a missing operator address blocks compilation") {
		val output = show(CustomTrustedListDraft(name = "bare"))

		output shouldContain "Operator addr. : (not set — required to compile)"
		output shouldContain "Scheme name    : (not set)"
		output shouldContain "Scheme info URI: (not set)"
	}

	test("spells out a missing provider address too") {
		val output = show(
			CustomTrustedListDraft(
				name = "partial",
				trustServiceProviders = listOf(
					TrustServiceProviderDraft(
						name = "Silent TSP",
						services = listOf(
							TrustServiceDraft(
								name = "CA",
								typeIdentifier = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
								status = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
								certificatePath = "/certs/ca.der",
							),
						),
					),
				),
			),
		)

		output shouldContain "● Silent TSP"
		output shouldContain "Address  : (not set — required to compile)"
		output shouldContain "Info URL : (not set)"
	}

	test("reports a partially filled address as incomplete rather than blank") {
		val output = show(
			CustomTrustedListDraft(
				name = "partial",
				schemeOperatorAddress = TrustedListAddress(
					streetAddress = "",
					locality = "Praha",
					countryName = "",
					electronicAddress = "",
				),
			),
		)

		output shouldContain "Operator addr. : Praha"
	}
})
