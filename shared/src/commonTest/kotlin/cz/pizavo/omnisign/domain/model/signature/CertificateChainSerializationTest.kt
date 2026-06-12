package cz.pizavo.omnisign.domain.model.signature

import cz.pizavo.omnisign.domain.model.validation.TimestampValidationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationIndication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Round-trips the certificate-chain model surface — [CertificateChainLink], the
 * [CertificateTrustSource] sealed hierarchy, and the [CertificateDetailSection] / [CertificateField]
 * detail dump — through kotlinx serialization.
 *
 * These types ride on [CertificateInfo.chain] (the signing certificate's chain) and
 * [TimestampValidationResult.chain] (the TSA's chain) and cross the server↔web boundary as JSON, so
 * their encoding has to survive a round trip intact: the certificate's raw DER bytes, the polymorphic
 * trust-source discriminator (a `data object` and two `data class` variants), and the nested detail
 * sections.
 *
 * A focused case also pins [CertificateChainLink]'s array-aware equality: its [CertificateChainLink.der]
 * field needs explicit `equals`/`hashCode` overrides that a plain data class would not provide.
 */
class CertificateChainSerializationTest : FunSpec({

    val json = Json

    fun link(
        commonName: String?,
        trustedVia: List<CertificateTrustSource>,
        der: ByteArray,
    ) = CertificateChainLink(
        commonName = commonName,
        subjectDN = "CN=$commonName, O=Test",
        selfSigned = false,
        trustedVia = trustedVia,
        details = listOf(
            CertificateDetailSection(
                title = "Subject",
                fields = listOf(CertificateField(label = "CN", value = commonName ?: "")),
            ),
        ),
        der = der,
    )

    test("each CertificateTrustSource variant round-trips polymorphically") {
        val sources = listOf(
            CertificateTrustSource.TrustedList("EU LOTL"),
            CertificateTrustSource.GlobalStore,
            CertificateTrustSource.ProfileStore("dev"),
        )

        sources.forEach { source ->
            val encoded = json.encodeToString(CertificateTrustSource.serializer(), source)
            json.decodeFromString(CertificateTrustSource.serializer(), encoded) shouldBe source
        }
    }

    test("CertificateChainLink preserves DER bytes, trust sources, and details across a round trip") {
        val original = link(
            commonName = "Signing Cert",
            trustedVia = listOf(CertificateTrustSource.GlobalStore, CertificateTrustSource.ProfileStore("dev")),
            der = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x0A, 0xFF.toByte(), 0x00, 0x07),
        )

        val encoded = json.encodeToString(CertificateChainLink.serializer(), original)
        val decoded = json.decodeFromString(CertificateChainLink.serializer(), encoded)

        decoded shouldBe original
    }

    test("CertificateChainLink equality is array-aware over the DER bytes") {
        val der = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x0A, 0xFF.toByte())
        val base = link("Signing Cert", listOf(CertificateTrustSource.GlobalStore), der.copyOf())
        val sameContentDistinctArray = link("Signing Cert", listOf(CertificateTrustSource.GlobalStore), der.copyOf())
        val differingDer = link("Signing Cert", listOf(CertificateTrustSource.GlobalStore), byteArrayOf(0x30, 0x00))

        base shouldBe sameContentDistinctArray
        base.hashCode() shouldBe sameContentDistinctArray.hashCode()
        base shouldNotBe differingDer
    }

    test("CertificateInfo carries its signing-certificate chain through serialization") {
        val original = CertificateInfo(
            subjectDN = "CN=Signer",
            issuerDN = "CN=Issuer",
            serialNumber = "0A1B2C",
            validFrom = Instant.fromEpochSeconds(1_600_000_000),
            validTo = Instant.fromEpochSeconds(1_900_000_000),
            chain = listOf(
                link("Signer", listOf(CertificateTrustSource.ProfileStore("dev")), byteArrayOf(1, 2, 3)),
                link("Issuer", listOf(CertificateTrustSource.TrustedList("EU LOTL")), byteArrayOf(4, 5, 6)),
            ),
        )

        val encoded = json.encodeToString(CertificateInfo.serializer(), original)
        val decoded = json.decodeFromString(CertificateInfo.serializer(), encoded)

        decoded.chain shouldHaveSize 2
        decoded.chain[0].der shouldBe byteArrayOf(1, 2, 3)
        decoded.chain[1].trustedVia.single().shouldBeInstanceOf<CertificateTrustSource.TrustedList>()
            .name shouldBe "EU LOTL"
        decoded shouldBe original
    }

    test("TimestampValidationResult carries its TSA chain through serialization") {
        val original = TimestampValidationResult(
            timestampId = "T-1",
            type = "Archive timestamp",
            indication = ValidationIndication.TOTAL_PASSED,
            productionTime = Instant.fromEpochSeconds(1_700_000_000),
            tsaSubjectDN = "CN=Test TSA",
            chain = listOf(
                link("Test TSA", listOf(CertificateTrustSource.TrustedList("EU LOTL")), byteArrayOf(1, 2, 3)),
                link("Root CA", listOf(CertificateTrustSource.GlobalStore), byteArrayOf(4, 5, 6)),
            ),
        )

        val encoded = json.encodeToString(TimestampValidationResult.serializer(), original)
        val decoded = json.decodeFromString(TimestampValidationResult.serializer(), encoded)

        decoded.chain shouldHaveSize 2
        decoded.chain[0].trustedVia.single().shouldBeInstanceOf<CertificateTrustSource.TrustedList>()
            .name shouldBe "EU LOTL"
        decoded.chain[1].trustedVia.single() shouldBe CertificateTrustSource.GlobalStore
        decoded.chain[1].der shouldBe byteArrayOf(4, 5, 6)
        decoded shouldBe original
    }
})
