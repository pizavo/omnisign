package cz.pizavo.omnisign.data.util

import cz.pizavo.omnisign.domain.model.signature.CertificateDetailSection
import cz.pizavo.omnisign.domain.model.signature.CertificateField
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.util.ASN1Dump
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.CertificatePolicies
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.PolicyInformation
import org.bouncycastle.asn1.x509.PolicyQualifierInfo
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.UserNotice
import org.bouncycastle.asn1.x509.qualified.QCStatement
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Parse a DER-encoded X.509 certificate into a complete, display-ready [CertificateDetailSection]
 * dump — every distinguished-name component and every extension. The common fields are decoded to
 * readable text; anything else (a non-standard DN attribute, an unrecognised extension) is kept by
 * its dotted OID with a structured ASN.1 dump of its value, so nothing the certificate carries is
 * hidden.
 *
 * JVM-only: uses `java.security` plus the BouncyCastle ASN.1 layer (already on the classpath via
 * DSS). Returns an empty list when [der] cannot be parsed as a certificate.
 */
fun extractCertificateDetails(der: ByteArray): List<CertificateDetailSection> {
    val certificate = runCatching {
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }.getOrNull() ?: return emptyList()

    return buildList {
        add(CertificateDetailSection("General", generalFields(certificate)))
        distinguishedNameFields(certificate.subjectX500Principal.encoded)
            .takeIf { it.isNotEmpty() }?.let { add(CertificateDetailSection("Subject", it)) }
        distinguishedNameFields(certificate.issuerX500Principal.encoded)
            .takeIf { it.isNotEmpty() }?.let { add(CertificateDetailSection("Issuer", it)) }
        add(CertificateDetailSection("Validity", validityFields(certificate)))
        add(CertificateDetailSection("Public Key", publicKeyFields(certificate)))
        extensionFields(certificate)
            .takeIf { it.isNotEmpty() }?.let { add(CertificateDetailSection("Extensions", it)) }
        add(CertificateDetailSection("Fingerprints", fingerprintFields(der)))
    }
}

/** Version, serial number, and signature algorithm. */
private fun generalFields(certificate: X509Certificate): List<CertificateField> = listOf(
    CertificateField("Version", "v${certificate.version}"),
    CertificateField("Serial Number", certificate.serialNumber.toHexColon()),
    CertificateField("Signature Algorithm", "${certificate.sigAlgName} (${certificate.sigAlgOID})"),
)

/** Every relative distinguished name, friendly-labelled where known and by dotted OID otherwise. */
private fun distinguishedNameFields(encodedName: ByteArray): List<CertificateField> =
    X500Name.getInstance(ASN1Primitive.fromByteArray(encodedName)).getRDNs().flatMap { rdn ->
        rdn.getTypesAndValues().map { atv ->
            CertificateField(
                label = DN_OID_NAMES[atv.getType().getId()] ?: atv.getType().getId(),
                value = attributeValueText(atv.getValue()),
            )
        }
    }

/**
 * The plain text of a distinguished-name attribute value. Returns the raw string for the common
 * string-typed values, so an in-value comma reads as `Name, Ph.D.` rather than the RFC 4514-escaped
 * `Name\, Ph.D.` that [IETFUtils.valueToString] produces for embedding inside a full DN string.
 * Falls back to that escaped representation for any non-string value type.
 */
private fun attributeValueText(value: ASN1Encodable): String =
    (value as? ASN1String)?.string
        ?: runCatching { IETFUtils.valueToString(value) }.getOrDefault(value.toString())

private fun validityFields(certificate: X509Certificate): List<CertificateField> = listOf(
    CertificateField("Not Before", certificate.notBefore.toInstant().toString()),
    CertificateField("Not After", certificate.notAfter.toInstant().toString()),
)

private fun publicKeyFields(certificate: X509Certificate): List<CertificateField> {
    val key = certificate.publicKey
    val size = when (key) {
        is RSAPublicKey -> "${key.modulus.bitLength()} bit"
        is ECPublicKey -> "${key.params.curve.field.fieldSize} bit"
        else -> null
    }
    return buildList {
        add(CertificateField("Algorithm", key.algorithm))
        size?.let { add(CertificateField("Key Size", it)) }
    }
}

/** Every extension, ordered by OID, decoded where common and ASN.1-dumped otherwise. */
private fun extensionFields(certificate: X509Certificate): List<CertificateField> {
    val critical = certificate.criticalExtensionOIDs.orEmpty()
    val all = (critical + certificate.nonCriticalExtensionOIDs.orEmpty()).sorted()
    return all.map { oid ->
        val name = EXT_OID_NAMES[oid] ?: oid
        val label = if (oid in critical) "$name (critical)" else name
        CertificateField(label, renderExtension(certificate, oid))
    }
}

private fun renderExtension(certificate: X509Certificate, oid: String): String = when (oid) {
    "2.5.29.15" -> keyUsageText(certificate)
    "2.5.29.37" -> extendedKeyUsageText(certificate)
    "2.5.29.19" -> basicConstraintsText(certificate)
    "2.5.29.17" -> alternativeNamesText(runCatching { certificate.subjectAlternativeNames }.getOrNull())
    "2.5.29.18" -> alternativeNamesText(runCatching { certificate.issuerAlternativeNames }.getOrNull())
    "2.5.29.14" -> subjectKeyIdentifierText(certificate)
    "2.5.29.35" -> authorityKeyIdentifierText(certificate)
    "2.5.29.31" -> crlDistributionPointsText(certificate)
    "2.5.29.32" -> certificatePoliciesText(certificate)
    "1.3.6.1.5.5.7.1.1" -> authorityInfoAccessText(certificate)
    "1.3.6.1.5.5.7.1.3" -> qcStatementsText(certificate)
    else -> asn1DumpText(certificate.getExtensionValue(oid))
}

private fun keyUsageText(certificate: X509Certificate): String {
    val bits = certificate.keyUsage ?: return "(none)"
    return KEY_USAGE_NAMES
        .filterIndexed { index, _ -> index < bits.size && bits[index] }
        .joinToString(", ").ifEmpty { "(none)" }
}

private fun extendedKeyUsageText(certificate: X509Certificate): String {
    val usages = runCatching { certificate.extendedKeyUsage }.getOrNull() ?: return "(none)"
    return usages.joinToString(", ") { EKU_OID_NAMES[it] ?: it }
}

private fun basicConstraintsText(certificate: X509Certificate): String = when (val pathLen = certificate.basicConstraints) {
    -1 -> "CA: false"
    Int.MAX_VALUE -> "CA: true, path length: unlimited"
    else -> "CA: true, path length: $pathLen"
}

private fun alternativeNamesText(names: Collection<List<*>>?): String {
    if (names.isNullOrEmpty()) return "(none)"
    return names.joinToString("\n") { entry ->
        val type = (entry.getOrNull(0) as? Int) ?: -1
        val typeName = GENERAL_NAME_TYPES[type] ?: "Type $type"
        val rawValue = entry.getOrNull(1)
        val valueText = if (type == 0 && rawValue is ByteArray) otherNameText(rawValue) else rawValue?.toString().orEmpty()
        "$typeName: $valueText"
    }
}

/**
 * Decode a SAN/IAN `otherName` entry (whose JDK representation is the raw DER of the
 * `SEQUENCE { type-id, [0] value }`) into "name: value" — the type by friendly name where known,
 * the value when it is a string — instead of the default `[B@…` byte-array rendering.
 */
private fun otherNameText(der: ByteArray): String =
    runCatching {
        val sequence = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))
        val typeId = (sequence.getObjectAt(0) as ASN1ObjectIdentifier).id
        val name = OTHER_NAME_TYPES[typeId] ?: typeId
        val value = runCatching {
            val inner = (sequence.getObjectAt(1) as ASN1TaggedObject).baseObject.toASN1Primitive()
            (inner as? ASN1String)?.string
        }.getOrNull()
        if (value != null) "$name: $value" else name
    }.getOrDefault("(unrecognised)")

private fun asn1DumpText(rawExtensionValue: ByteArray?): String {
    if (rawExtensionValue == null) return ""
    return runCatching {
        val octets = (ASN1Primitive.fromByteArray(rawExtensionValue) as ASN1OctetString).octets
        ASN1Dump.dumpAsString(ASN1Primitive.fromByteArray(octets), true).trim()
    }.getOrElse { rawExtensionValue.toHex() }
}

/** Indentation for the qualifier lines nested under a certificate policy. */
private val ExtIndent = " ".repeat(2)

/** The extension value as an ASN.1 object with its `extnValue` OCTET STRING wrapper removed. */
private fun extensionValueAsn1(certificate: X509Certificate, oid: String): ASN1Primitive? =
    certificate.getExtensionValue(oid)?.let { raw ->
        runCatching {
            ASN1Primitive.fromByteArray((ASN1Primitive.fromByteArray(raw) as ASN1OctetString).octets)
        }.getOrNull()
    }

/** Hex key identifier of the Subject Key Identifier extension. */
private fun subjectKeyIdentifierText(certificate: X509Certificate): String =
    runCatching {
        SubjectKeyIdentifier.getInstance(extensionValueAsn1(certificate, "2.5.29.14")).keyIdentifier.toHexColon()
    }.getOrElse { asn1DumpText(certificate.getExtensionValue("2.5.29.14")) }

/** Hex key identifier of the Authority Key Identifier extension. */
private fun authorityKeyIdentifierText(certificate: X509Certificate): String =
    runCatching {
        AuthorityKeyIdentifier.getInstance(extensionValueAsn1(certificate, "2.5.29.35")).keyIdentifier?.toHexColon()
            ?: asn1DumpText(certificate.getExtensionValue("2.5.29.35"))
    }.getOrElse { asn1DumpText(certificate.getExtensionValue("2.5.29.35")) }

/** One "method: location" line per access description of the Authority Information Access extension. */
private fun authorityInfoAccessText(certificate: X509Certificate): String =
    runCatching {
        AuthorityInformationAccess.getInstance(extensionValueAsn1(certificate, "1.3.6.1.5.5.7.1.1"))
            .accessDescriptions.joinToString("\n") { description ->
                val method = ACCESS_METHOD_NAMES[description.accessMethod.id] ?: description.accessMethod.id
                "$method: ${generalNameText(description.accessLocation)}"
            }
    }.getOrElse { asn1DumpText(certificate.getExtensionValue("1.3.6.1.5.5.7.1.1")) }

/** One distribution-point URL per line of the CRL Distribution Points extension. */
private fun crlDistributionPointsText(certificate: X509Certificate): String =
    runCatching {
        CRLDistPoint.getInstance(extensionValueAsn1(certificate, "2.5.29.31")).distributionPoints
            .flatMap { point ->
                val name = point.distributionPoint
                if (name?.type == DistributionPointName.FULL_NAME) {
                    GeneralNames.getInstance(name.name).names.map { generalNameText(it) }
                } else {
                    emptyList()
                }
            }
            .joinToString("\n")
            .ifEmpty { asn1DumpText(certificate.getExtensionValue("2.5.29.31")) }
    }.getOrElse { asn1DumpText(certificate.getExtensionValue("2.5.29.31")) }

/** Each policy OID with its CPS URI and user-notice qualifiers indented beneath it. */
private fun certificatePoliciesText(certificate: X509Certificate): String =
    runCatching {
        CertificatePolicies.getInstance(extensionValueAsn1(certificate, "2.5.29.32")).policyInformation
            .joinToString("\n") { policyInformationText(it) }
    }.getOrElse { asn1DumpText(certificate.getExtensionValue("2.5.29.32")) }

private fun policyInformationText(info: PolicyInformation): String = buildString {
    append(info.policyIdentifier.id)
    info.policyQualifiers?.toArray()?.forEach { qualifier ->
        val qualifierInfo = PolicyQualifierInfo.getInstance(qualifier)
        when (qualifierInfo.policyQualifierId.id) {
            "1.3.6.1.5.5.7.2.1" -> append("\n").append(ExtIndent).append("CPS: ")
                .append((qualifierInfo.qualifier as? ASN1String)?.string ?: qualifierInfo.qualifier.toString())
            "1.3.6.1.5.5.7.2.2" -> append("\n").append(ExtIndent).append("Notice: ")
                .append(userNoticeText(qualifierInfo.qualifier))
        }
    }
}

private fun userNoticeText(qualifier: ASN1Encodable): String =
    runCatching { UserNotice.getInstance(qualifier).explicitText?.string ?: qualifier.toString() }
        .getOrDefault(qualifier.toString())

/** Each QC statement by ETSI EN 319 412-5 name, with PDS URLs and QC types expanded. */
private fun qcStatementsText(certificate: X509Certificate): String =
    runCatching {
        ASN1Sequence.getInstance(extensionValueAsn1(certificate, "1.3.6.1.5.5.7.1.3")).toArray()
            .joinToString("\n") { qcStatementText(QCStatement.getInstance(it)) }
    }.getOrElse { asn1DumpText(certificate.getExtensionValue("1.3.6.1.5.5.7.1.3")) }

private fun qcStatementText(statement: QCStatement): String {
    val id = statement.statementId.id
    val name = QC_STATEMENT_NAMES[id] ?: id
    return when (id) {
        "0.4.0.1862.1.5" -> "$name: ${qcPdsText(statement.statementInfo)}"
        "0.4.0.1862.1.6" -> "$name: ${qcTypeText(statement.statementInfo)}"
        else -> name
    }
}

private fun qcPdsText(info: ASN1Encodable?): String =
    runCatching {
        ASN1Sequence.getInstance(info).toArray().joinToString(", ") { location ->
            val pds = ASN1Sequence.getInstance(location)
            val url = (pds.getObjectAt(0) as ASN1String).string
            val language = (pds.getObjectAt(1) as ASN1String).string
            "$url ($language)"
        }
    }.getOrDefault(info?.toString().orEmpty())

private fun qcTypeText(info: ASN1Encodable?): String =
    runCatching {
        ASN1Sequence.getInstance(info).toArray().joinToString(", ") { type ->
            val id = (type as ASN1ObjectIdentifier).id
            QC_TYPE_NAMES[id] ?: id
        }
    }.getOrDefault(info?.toString().orEmpty())

/** Plain string of a general name (URI, email, DNS, …) for the access/CRL decoders. */
private fun generalNameText(name: GeneralName): String =
    (name.name as? ASN1String)?.string ?: name.name.toString()

private fun fingerprintFields(der: ByteArray): List<CertificateField> = listOf(
    CertificateField("SHA-1", digestHexColon(der, "SHA-1")),
    CertificateField("SHA-256", digestHexColon(der, "SHA-256")),
)

private fun digestHexColon(data: ByteArray, algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(data).toHexColon()

private fun ByteArray.toHexColon(): String = joinToString(":") { "%02X".format(it) }

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

private fun BigInteger.toHexColon(): String =
    toString(16).uppercase()
        .let { if (it.length % 2 == 1) "0$it" else it }
        .chunked(2).joinToString(":")

private val KEY_USAGE_NAMES = listOf(
    "Digital Signature", "Non Repudiation", "Key Encipherment", "Data Encipherment",
    "Key Agreement", "Certificate Sign", "CRL Sign", "Encipher Only", "Decipher Only",
)

private val GENERAL_NAME_TYPES = mapOf(
    0 to "Other Name", 1 to "Email", 2 to "DNS", 3 to "X.400 Address",
    4 to "Directory Name", 5 to "EDI Party", 6 to "URI", 7 to "IP Address", 8 to "Registered ID",
)

private val EKU_OID_NAMES = mapOf(
    "1.3.6.1.5.5.7.3.1" to "Server Authentication",
    "1.3.6.1.5.5.7.3.2" to "Client Authentication",
    "1.3.6.1.5.5.7.3.3" to "Code Signing",
    "1.3.6.1.5.5.7.3.4" to "Email Protection",
    "1.3.6.1.5.5.7.3.8" to "Time Stamping",
    "1.3.6.1.5.5.7.3.9" to "OCSP Signing",
)

private val DN_OID_NAMES = mapOf(
    "2.5.4.3" to "Common Name (CN)",
    "2.5.4.10" to "Organization (O)",
    "2.5.4.11" to "Organizational Unit (OU)",
    "2.5.4.6" to "Country (C)",
    "2.5.4.7" to "Locality (L)",
    "2.5.4.8" to "State/Province (ST)",
    "2.5.4.9" to "Street",
    "2.5.4.5" to "Serial Number",
    "2.5.4.65" to "Pseudonym",
    "2.5.4.12" to "Title",
    "2.5.4.42" to "Given Name",
    "2.5.4.4" to "Surname",
    "2.5.4.13" to "Description",
    "2.5.4.15" to "Business Category",
    "2.5.4.17" to "Postal Code",
    "2.5.4.16" to "Postal Address",
    "2.5.4.97" to "Organization Identifier",
    "1.2.840.113549.1.9.1" to "Email",
    "0.9.2342.19200300.100.1.25" to "Domain Component (DC)",
    "0.9.2342.19200300.100.1.1" to "User ID (UID)",
)

private val EXT_OID_NAMES = mapOf(
    "2.5.29.15" to "Key Usage",
    "2.5.29.37" to "Extended Key Usage",
    "2.5.29.19" to "Basic Constraints",
    "2.5.29.17" to "Subject Alternative Name",
    "2.5.29.18" to "Issuer Alternative Name",
    "2.5.29.31" to "CRL Distribution Points",
    "1.3.6.1.5.5.7.1.1" to "Authority Information Access",
    "2.5.29.32" to "Certificate Policies",
    "2.5.29.14" to "Subject Key Identifier",
    "2.5.29.35" to "Authority Key Identifier",
    "1.3.6.1.5.5.7.1.3" to "QC Statements",
    "2.5.29.30" to "Name Constraints",
    "2.5.29.36" to "Policy Constraints",
    "2.5.29.9" to "Subject Directory Attributes",
    "1.3.6.1.5.5.7.48.1.5" to "OCSP No-Check",
)

private val ACCESS_METHOD_NAMES = mapOf(
    "1.3.6.1.5.5.7.48.1" to "OCSP",
    "1.3.6.1.5.5.7.48.2" to "CA Issuers",
)

private val QC_STATEMENT_NAMES = mapOf(
    "0.4.0.1862.1.1" to "QcCompliance",
    "0.4.0.1862.1.2" to "QcLimitValue",
    "0.4.0.1862.1.3" to "QcRetentionPeriod",
    "0.4.0.1862.1.4" to "QcSSCD",
    "0.4.0.1862.1.5" to "QcPDS",
    "0.4.0.1862.1.6" to "QcType",
)

private val QC_TYPE_NAMES = mapOf(
    "0.4.0.1862.1.6.1" to "eSign",
    "0.4.0.1862.1.6.2" to "eSeal",
    "0.4.0.1862.1.6.3" to "Web",
)

private val OTHER_NAME_TYPES = mapOf(
    "1.3.6.1.4.1.311.20.2.3" to "User Principal Name",
    "1.3.6.1.5.5.7.8.3" to "Permanent Identifier",
    "1.3.6.1.5.5.7.8.5" to "XmppAddr",
)
