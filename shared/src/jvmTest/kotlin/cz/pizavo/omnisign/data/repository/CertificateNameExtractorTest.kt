package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import javax.security.auth.x500.X500Principal

/**
 * Verifies [extractSubjectCN] correctly extracts the Common Name from various
 * X.500 distinguished name formats.
 */
class CertificateNameExtractorTest : FunSpec({
	
	test("extracts CN from a standard DN") {
		val principal = X500Principal("CN=PostSignum Qualified CA 4, O=Česká pošta\\, s.p., C=CZ")
		extractSubjectCN(principal) shouldBe "PostSignum Qualified CA 4"
	}
	
	test("extracts CN when it is the only RDN") {
		val principal = X500Principal("CN=Test Certificate")
		extractSubjectCN(principal) shouldBe "Test Certificate"
	}
	
	test("falls back to full DN when CN is absent") {
		val principal = X500Principal("O=Acme Corp, C=US")
		extractSubjectCN(principal) shouldBe principal.name
	}
	
	test("handles multi-valued DN with CN in the middle") {
		val principal = X500Principal("C=CZ, CN=My Cert, O=Org")
		extractSubjectCN(principal) shouldBe "My Cert"
	}
})

