package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import cz.pizavo.omnisign.domain.model.config.TrustedListAddress
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.error.OperationError
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader
import eu.europa.esig.dss.spi.DSSUtils
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

/**
 * Verifies that [DssTrustedListCompilerAdapter] returns a source DSS can load for the list it
 * wrote, and tells a draft the compiler rejects from a list it could not write.
 */
class DssTrustedListCompilerAdapterTest : FunSpec({

	val adapter = DssTrustedListCompilerAdapter(TrustedListCompiler())
	val tmpDir = tempdir()

	/** Write a throwaway self-signed certificate as DER under [fileName] and return its path. */
	fun certificatePath(fileName: String): String {
		val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		val name = X500Name("CN=Adapter Test CA,O=OmniSign Test")
		val now = System.currentTimeMillis()
		val holder = JcaX509v3CertificateBuilder(
			name,
			BigInteger.valueOf(now),
			Date(now - 1000L),
			Date(now + 365L * 24 * 60 * 60 * 1000),
			name,
			keyPair.public,
		).build(JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private))
		val certificate = JcaX509CertificateConverter().getCertificate(holder)
		return File(tmpDir, fileName).also { it.writeBytes(certificate.encoded) }.absolutePath
	}

	val address = TrustedListAddress(
		streetAddress = "Technicka 2",
		locality = "Praha",
		countryName = "CZ",
		electronicAddress = "mailto:tl@omnisign.test",
	)

	/** A draft complete enough to compile, whose one service is anchored by [certificatePath]. */
	fun draft(name: String, certificatePath: String) = CustomTrustedListDraft(
		name = name,
		territory = "CZ",
		schemeOperatorName = "OmniSign Test Operator",
		schemeOperatorAddress = address,
		schemeName = "OmniSign internal trust anchors",
		schemeInformationUri = "https://omnisign.test/tl/about",
		trustServiceProviders = listOf(
			TrustServiceProviderDraft(
				name = "OmniSign Test TSP",
				tradeName = null,
				address = address,
				infoUrl = "https://tsp.omnisign.test",
				services = listOf(
					TrustServiceDraft(
						name = "Internal CA",
						typeIdentifier = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
						status = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted",
						certificatePath = certificatePath,
					),
				),
			),
		),
	)

	/** How many times [error]'s message says that compiling failed. */
	fun compileFailureMentions(error: OperationError): Int =
		Regex("Failed to compile trusted list").findAll(error.message).count()

	test("the written list's source is one DSS loads, even under a path with spaces") {
		val target = File(tmpDir, "trusted lists/corp list.xml")

		val source = adapter.compileTo(draft("Corp", certificatePath("corp.der")), target.absolutePath)
			.shouldBeRight()

		val loader = FileCacheDataLoader(CommonsDataLoader()).apply { setFileCacheDirectory(tempdir()) }
		DSSUtils.toByteArray(loader.getDocument(source, true)) shouldBe target.readBytes()
	}

	test("a draft the compiler rejects is an invalid configuration named once, and nothing is written") {
		val target = File(tmpDir, "rejected.xml")
		val broken = draft("Broken list", File(tmpDir, "absent.der").absolutePath)

		val error = adapter.compileTo(broken, target.absolutePath).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		compileFailureMentions(error) shouldBe 1
		target.exists() shouldBe false
	}

	test("compiling alone says once that a rejected draft failed to compile") {
		val broken = draft("Broken list", File(tmpDir, "missing.der").absolutePath)

		val error = adapter.compile(broken).shouldBeLeft()

		error.shouldBeInstanceOf<ConfigurationError.InvalidConfiguration>()
		compileFailureMentions(error) shouldBe 1
	}

	test("a compiled list that cannot be written is a save failure") {
		val occupied = File(tmpDir, "occupied").apply { mkdirs() }

		adapter.compileTo(draft("Corp", certificatePath("occupied.der")), occupied.absolutePath).shouldBeLeft()
			.shouldBeInstanceOf<ConfigurationError.SaveFailed>()
	}
})
