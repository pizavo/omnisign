package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustedListAddress
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import eu.europa.esig.trustedlist.TrustedListFacade
import eu.europa.esig.trustedlist.jaxb.tsl.TrustStatusListType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * Verifies [TrustedListCompiler] emits an ETSI TS 119612 document that says what the draft said.
 *
 * Every structural assertion runs against the *reparsed* output rather than the raw text: the
 * compiled XML is fed back through [TrustedListFacade], which validates it against the TSL schema on
 * the way in. That makes each case a round-trip — a document DSS would reject never reaches an
 * assertion — and it means a malformed list fails here rather than silently at load time in a
 * `TLSource`.
 */
class TrustedListCompilerTest : FunSpec({

	val compiler = TrustedListCompiler()
	val tmpDir = tempdir()

	val serviceType = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC"
	val granted = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted"

	/** A throwaway self-signed certificate to stand in for a trust anchor. */
	fun selfSigned(cn: String): X509Certificate {
		val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		val name = X500Name("CN=$cn,O=OmniSign Test")
		val now = System.currentTimeMillis()
		val builder = JcaX509v3CertificateBuilder(
			name,
			BigInteger.valueOf(now),
			Date(now - 1000L),
			Date(now + 365L * 24 * 60 * 60 * 1000),
			name,
			keyPair.public,
		)
		val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
		return JcaX509CertificateConverter().getCertificate(builder.build(signer))
	}

	/** Write [certificate] to a DER file and return its absolute path. */
	fun derPath(fileName: String, certificate: X509Certificate): String =
		File(tmpDir, fileName).also { it.writeBytes(certificate.encoded) }.absolutePath

	/** Write [certificate] to a PEM file and return its absolute path. */
	fun pemPath(fileName: String, certificate: X509Certificate): String {
		val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded)
		val pem = "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
		return File(tmpDir, fileName).also { it.writeText(pem) }.absolutePath
	}

	/** Reparse compiled XML through the schema-validating facade. */
	fun parse(xml: String): TrustStatusListType = TrustedListFacade.newFacade().unmarshall(xml)

	val operatorAddress = TrustedListAddress(
		streetAddress = "Technicka 2",
		locality = "Praha",
		countryName = "CZ",
		postalCode = "16000",
		electronicAddress = "mailto:tl@omnisign.test",
	)

	fun draftWith(
		vararg providers: TrustServiceProviderDraft,
		name: String = "Internal anchors",
		territory: String = "CZ",
		operator: String = "OmniSign Test Operator",
		address: TrustedListAddress? = operatorAddress,
	) = CustomTrustedListDraft(
		name = name,
		territory = territory,
		schemeOperatorName = operator,
		schemeOperatorAddress = address,
		schemeName = "OmniSign internal trust anchors",
		schemeInformationUri = "https://omnisign.test/tl/about",
		trustServiceProviders = providers.toList(),
	)

	fun serviceDraft(certPath: String, name: String = "Internal CA") =
		TrustServiceDraft(
			name = name,
			typeIdentifier = serviceType,
			status = granted,
			certificatePath = certPath,
		)

	val providerAddress = TrustedListAddress(
		streetAddress = "Namesti Miru 1",
		locality = "Brno",
		countryName = "CZ",
		electronicAddress = "https://tsp.omnisign.test/contact",
	)

	fun providerWith(
		vararg services: TrustServiceDraft,
		name: String = "OmniSign Test TSP",
		tradeName: String? = null,
		infoUrl: String = "https://tsp.omnisign.test",
		address: TrustedListAddress? = providerAddress,
	) = TrustServiceProviderDraft(
		name = name,
		tradeName = tradeName,
		address = address,
		infoUrl = infoUrl,
		services = services.toList(),
	)

	test("tags the root element so the document satisfies the trusted-list schema") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("tag.der", selfSigned("Tag CA"))), name = "OmniSign Test TSP"),
		)

		parse(compiler.compile(draft)).tslTag shouldBe "http://uri.etsi.org/19612/TSLTag"
	}

	test("emits scheme information carrying the ETSI version, type, territory and operator") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("scheme.der", selfSigned("Scheme CA"))), name = "OmniSign Test TSP"),
		)

		val info = parse(compiler.compile(draft)).schemeInformation

		info.tslVersionIdentifier shouldBe BigInteger.valueOf(5)
		info.tslSequenceNumber shouldBe BigInteger.ONE
		info.tslType shouldBe "http://uri.etsi.org/TrstSvc/TrustedList/TSLType/EUgeneric"
		info.schemeTerritory shouldBe "CZ"
		info.schemeOperatorName.name.single().value shouldBe "OmniSign Test Operator"
		info.schemeOperatorName.name.single().lang shouldBe "en"
	}

	test("emits the scheme operator's postal and electronic address") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("addr.der", selfSigned("Addr CA"))), name = "TSP"),
		)

		val address = parse(compiler.compile(draft)).schemeInformation.schemeOperatorAddress

		val postal = address.postalAddresses.postalAddress.single()
		postal.streetAddress shouldBe "Technicka 2"
		postal.locality shouldBe "Praha"
		postal.countryName shouldBe "CZ"
		postal.postalCode shouldBe "16000"
		postal.stateOrProvince shouldBe null
		postal.lang shouldBe "en"
		address.electronicAddress.uri.single().value shouldBe "mailto:tl@omnisign.test"
	}

	test("names the scheme, where to read about it, and how status is determined") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("policy.der", selfSigned("Policy CA"))), name = "TSP"),
		)

		val info = parse(compiler.compile(draft)).schemeInformation

		info.schemeName.name.single().value shouldBe "OmniSign internal trust anchors"
		info.schemeInformationURI.uri.single().value shouldBe "https://omnisign.test/tl/about"
		info.statusDeterminationApproach shouldBe
			"http://uri.etsi.org/TrstSvc/TrustedList/StatusDetn/appropriate"
		info.historicalInformationPeriod shouldBe BigInteger.valueOf(65535)
	}

	test("refuses an incomplete draft by naming every missing field at once") {
		val draft = CustomTrustedListDraft(
			name = "Half finished",
			territory = "CZ",
			trustServiceProviders = listOf(
				providerWith(serviceDraft(derPath("partial.der", selfSigned("Partial CA"))), name = "TSP"),
			),
		)

		val failure = shouldThrow<IllegalStateException> { compiler.compile(draft) }

		failure.message.shouldNotBeNull().let { message ->
			message shouldContain "Half finished"
			message shouldContain "scheme operator name"
			message shouldContain "scheme name"
			message shouldContain "scheme information URI"
			message shouldContain "scheme operator address"
		}
	}

	test("names the individual address fields a partially filled address is missing") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("blank.der", selfSigned("Blank CA"))), name = "TSP"),
			address = TrustedListAddress(
				streetAddress = "  ",
				locality = "Praha",
				countryName = "",
				electronicAddress = "mailto:tl@omnisign.test",
			),
		)

		val failure = shouldThrow<IllegalStateException> { compiler.compile(draft) }

		failure.message.shouldNotBeNull().let { message ->
			message shouldContain "street address"
			message shouldContain "country"
		}
	}

	test("schedules the next update a year after the issue date") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("next.der", selfSigned("Next CA"))), name = "OmniSign Test TSP"),
		)

		val info = parse(compiler.compile(draft)).schemeInformation

		val issued = info.listIssueDateTime
		val next = info.nextUpdate.dateTime.shouldNotBeNull()
		next.year - issued.year shouldBe 1
	}

	test("embeds the service certificate as DER whichever encoding it was loaded from") {
		val certificate = selfSigned("Shared CA")
		val fromDer = draftWith(
			providerWith(serviceDraft(derPath("shared.der", certificate)), name = "TSP"),
		)
		val fromPem = draftWith(
			providerWith(serviceDraft(pemPath("shared.pem", certificate)), name = "TSP"),
		)

		val derEmbedded = parse(compiler.compile(fromDer))
			.trustServiceProviderList.trustServiceProvider.single()
			.tspServices.tspService.single()
			.serviceInformation.serviceDigitalIdentity.digitalId.single()
			.x509Certificate
		val pemEmbedded = parse(compiler.compile(fromPem))
			.trustServiceProviderList.trustServiceProvider.single()
			.tspServices.tspService.single()
			.serviceInformation.serviceDigitalIdentity.digitalId.single()
			.x509Certificate

		derEmbedded.toList() shouldBe certificate.encoded.toList()
		pemEmbedded.toList() shouldBe certificate.encoded.toList()
	}

	test("names the service type, name and status") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("svc.der", selfSigned("Svc CA")), name = "Qualified CA"), name = "TSP"),
		)

		val svcInfo = parse(compiler.compile(draft))
			.trustServiceProviderList.trustServiceProvider.single()
			.tspServices.tspService.single()
			.serviceInformation

		svcInfo.serviceTypeIdentifier shouldBe serviceType
		svcInfo.serviceStatus shouldBe granted
		svcInfo.serviceName.name.single().value shouldBe "Qualified CA"
		svcInfo.statusStartingTime.shouldNotBeNull()
	}

	test("carries the trade name only when the draft sets one") {
		val certPath = derPath("trade.der", selfSigned("Trade CA"))
		val withTrade = draftWith(
			providerWith(serviceDraft(certPath), name = "Legal Entity Name", tradeName = "Trading As"),
		)
		val withoutTrade = draftWith(
			providerWith(serviceDraft(certPath, name = "Legal Entity Name")),
		)

		val present = parse(compiler.compile(withTrade))
			.trustServiceProviderList.trustServiceProvider.single().tspInformation
		val absent = parse(compiler.compile(withoutTrade))
			.trustServiceProviderList.trustServiceProvider.single().tspInformation

		present.tspName.name.single().value shouldBe "Legal Entity Name"
		present.tspTradeName.name.single().value shouldBe "Trading As"
		absent.tspTradeName shouldBe null
	}

	test("carries each provider's information URI and address") {
		val draft = draftWith(
			providerWith(
				serviceDraft(derPath("uri.der", selfSigned("Uri CA"))),
				infoUrl = "https://omnisign.test/tsp",
			),
		)

		val info = parse(compiler.compile(draft))
			.trustServiceProviderList.trustServiceProvider.single().tspInformation

		info.tspInformationURI.uri.single().value shouldBe "https://omnisign.test/tsp"
		info.tspInformationURI.uri.single().lang shouldBe "en"
		val postal = info.tspAddress.postalAddresses.postalAddress.single()
		postal.streetAddress shouldBe "Namesti Miru 1"
		postal.locality shouldBe "Brno"
		postal.countryName shouldBe "CZ"
		info.tspAddress.electronicAddress.uri.single().value shouldBe
			"https://tsp.omnisign.test/contact"
	}

	test("refuses a provider that is missing what the schema demands of every provider") {
		val draft = draftWith(
			providerWith(
				serviceDraft(derPath("noinfo.der", selfSigned("NoInfo CA"))),
				name = "Silent TSP",
				infoUrl = "   ",
				address = null,
			),
		)

		val failure = shouldThrow<IllegalStateException> { compiler.compile(draft) }

		failure.message.shouldNotBeNull().let { message ->
			message shouldContain "provider 'Silent TSP' information URI"
			message shouldContain "provider 'Silent TSP' address"
		}
	}

	test("refuses a provider that lists no services") {
		val draft = draftWith(providerWith(name = "Empty TSP"))

		val failure = shouldThrow<IllegalStateException> { compiler.compile(draft) }

		failure.message.shouldNotBeNull() shouldContain "at least one service for provider 'Empty TSP'"
	}

	test("carries every provider and every service from the draft") {
		val draft = draftWith(
			providerWith(
				serviceDraft(derPath("a.der", selfSigned("A CA")), name = "Service A"),
				serviceDraft(derPath("b.der", selfSigned("B CA")), name = "Service B"),
				name = "First TSP",
			),
			providerWith(serviceDraft(derPath("c.der", selfSigned("C CA")), name = "Service C"), name = "Second TSP"),
		)

		val providers = parse(compiler.compile(draft)).trustServiceProviderList.trustServiceProvider

		providers.map { it.tspInformation.tspName.name.single().value } shouldBe
			listOf("First TSP", "Second TSP")
		providers.flatMap { tsp ->
			tsp.tspServices.tspService.map { it.serviceInformation.serviceName.name.single().value }
		} shouldBe listOf("Service A", "Service B", "Service C")
	}

	test("writes the compiled document, creating missing parent directories") {
		val draft = draftWith(
			providerWith(serviceDraft(derPath("out.der", selfSigned("Out CA"))), name = "TSP"),
		)
		val target = File(tmpDir, "nested/deeper/list.xml")

		compiler.compileTo(draft, target)

		target.exists() shouldBe true
		parse(target.readText()).schemeInformation.schemeTerritory shouldBe "CZ"
	}

	test("fails naming the draft when a service certificate cannot be read") {
		val draft = draftWith(
			providerWith(serviceDraft(File(tmpDir, "absent.der").absolutePath), name = "TSP"),
			name = "Broken list",
		)

		val failure = shouldThrow<IllegalStateException> { compiler.compile(draft) }

		failure.message.shouldNotBeNull() shouldContain "Broken list"
	}
})
