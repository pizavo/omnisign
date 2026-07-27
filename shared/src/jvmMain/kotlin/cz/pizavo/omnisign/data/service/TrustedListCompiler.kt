package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustedListAddress
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import eu.europa.esig.trustedlist.TrustedListFacade
import eu.europa.esig.trustedlist.jaxb.tsl.AddressType
import eu.europa.esig.trustedlist.jaxb.tsl.DigitalIdentityListType
import eu.europa.esig.trustedlist.jaxb.tsl.DigitalIdentityType
import eu.europa.esig.trustedlist.jaxb.tsl.ElectronicAddressType
import eu.europa.esig.trustedlist.jaxb.tsl.InternationalNamesType
import eu.europa.esig.trustedlist.jaxb.tsl.MultiLangNormStringType
import eu.europa.esig.trustedlist.jaxb.tsl.NextUpdateType
import eu.europa.esig.trustedlist.jaxb.tsl.NonEmptyMultiLangURIListType
import eu.europa.esig.trustedlist.jaxb.tsl.NonEmptyMultiLangURIType
import eu.europa.esig.trustedlist.jaxb.tsl.PostalAddressListType
import eu.europa.esig.trustedlist.jaxb.tsl.PostalAddressType
import eu.europa.esig.trustedlist.jaxb.tsl.TSLSchemeInformationType
import eu.europa.esig.trustedlist.jaxb.tsl.TSPInformationType
import eu.europa.esig.trustedlist.jaxb.tsl.TSPServiceInformationType
import eu.europa.esig.trustedlist.jaxb.tsl.TSPServicesListType
import eu.europa.esig.trustedlist.jaxb.tsl.TSPServiceType
import eu.europa.esig.trustedlist.jaxb.tsl.TSPType
import eu.europa.esig.trustedlist.jaxb.tsl.TrustServiceProviderListType
import eu.europa.esig.trustedlist.jaxb.tsl.TrustStatusListType
import java.io.File
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.util.GregorianCalendar
import javax.xml.datatype.DatatypeFactory

/**
 * Compiles a [CustomTrustedListDraft] into a standards-conformant ETSI TS 119612 XML document
 * using the JAXB object model from `specs-trusted-list`.
 *
 * The resulting XML is unsigned — it is intended for use with DSS [eu.europa.esig.dss.tsl.source.TLSource] instances
 * whose signature verification is either disabled or handled separately.
 */
class TrustedListCompiler {

    private val xmlCalendarFactory = DatatypeFactory.newInstance()

    /**
     * Compile [draft] to an ETSI TS 119612 XML string.
     *
     * @param draft The in-progress TL definition to compile.
     * @return The serialized XML as a [String].
     */
    fun compile(draft: CustomTrustedListDraft): String {
        requireCompilable(draft)
        return try {
            TrustedListFacade.newFacade().marshall(buildTrustStatusList(draft))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to compile trusted list '${draft.name}': ${e.message}", e)
        }
    }

    /**
     * Reject a draft that is missing something ETSI TS 119612 requires, naming every gap at once.
     *
     * Marshalling validates against the schema and reports only the first violation it meets, as an
     * `cvc-complex-type` message against an element name the user never typed. Checking here instead
     * turns "the draft is incomplete" — the ordinary state of a wizard abandoned halfway — into a
     * list of fields to go and fill in.
     *
     * @throws IllegalStateException when any mandatory field is absent or blank.
     */
    private fun requireCompilable(draft: CustomTrustedListDraft) {
        val missing = buildList {
            if (draft.schemeOperatorName.isBlank()) add("scheme operator name")
            if (draft.schemeName.isBlank()) add("scheme name")
            if (draft.schemeInformationUri.isBlank()) add("scheme information URI")
            if (draft.statusDeterminationApproach.isBlank()) add("status determination approach")
            addAll(missingAddressFields(draft.schemeOperatorAddress, "scheme operator"))
            draft.trustServiceProviders.forEach { tsp ->
                val label = "provider '${tsp.name}'"
                if (tsp.name.isBlank()) add("a provider name")
                if (tsp.infoUrl.isBlank()) add("$label information URI")
                if (tsp.services.isEmpty()) add("at least one service for $label")
                addAll(missingAddressFields(tsp.address, label))
            }
        }
        check(missing.isEmpty()) {
            "Trusted list '${draft.name}' cannot be compiled — ETSI TS 119612 requires: " +
                missing.joinToString(", ")
        }
    }

    /**
     * The mandatory parts of [address] that are absent or blank, each prefixed with [owner] so the
     * caller can tell the scheme operator's address from a provider's.
     *
     * @param address The address to inspect, or `null` when none was supplied at all.
     * @param owner Human-readable name of whoever the address belongs to.
     * @return Field descriptions to report, empty when the address is complete.
     */
    private fun missingAddressFields(address: TrustedListAddress?, owner: String): List<String> =
        when (address) {
            null -> listOf("$owner address")
            else -> buildList {
                if (address.streetAddress.isBlank()) add("$owner street address")
                if (address.locality.isBlank()) add("$owner locality")
                if (address.countryName.isBlank()) add("$owner country")
                if (address.electronicAddress.isBlank()) add("$owner electronic address")
            }
        }

    /**
     * Compile [draft] and write the resulting XML to [outputFile], creating parent
     * directories if necessary.
     *
     * @param draft The draft to compile.
     * @param outputFile Destination file.
     */
    fun compileTo(draft: CustomTrustedListDraft, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(compile(draft))
    }

    private fun buildTrustStatusList(draft: CustomTrustedListDraft): TrustStatusListType {
        val tsl = TrustStatusListType()
        tsl.setTSLTag(TSL_TAG_URI)
        tsl.setSchemeInformation(buildSchemeInformation(draft))
        if (draft.trustServiceProviders.isNotEmpty()) {
            tsl.setTrustServiceProviderList(buildTspList(draft))
        }
        return tsl
    }

    private fun buildSchemeInformation(draft: CustomTrustedListDraft): TSLSchemeInformationType {
        val now = GregorianCalendar()
        val nextYear = GregorianCalendar().apply { add(GregorianCalendar.YEAR, 1) }
        val info = TSLSchemeInformationType()
        info.setTSLVersionIdentifier(BigInteger.valueOf(ETSI_TL_VERSION))
        info.setTSLSequenceNumber(BigInteger.ONE)
        info.setTSLType(TSL_TYPE_URI)
        info.setSchemeTerritory(draft.territory)
        info.setSchemeOperatorName(internationalNames(draft.schemeOperatorName))
        info.setSchemeOperatorAddress(buildAddress(requireNotNull(draft.schemeOperatorAddress)))
        info.setSchemeName(internationalNames(draft.schemeName))
        info.setSchemeInformationURI(multiLangUriList(draft.schemeInformationUri))
        info.setStatusDeterminationApproach(draft.statusDeterminationApproach)
        info.setHistoricalInformationPeriod(BigInteger.valueOf(draft.historicalInformationPeriod.toLong()))
        info.setListIssueDateTime(xmlCalendarFactory.newXMLGregorianCalendar(now))
        info.setNextUpdate(NextUpdateType().also {
            it.setDateTime(xmlCalendarFactory.newXMLGregorianCalendar(nextYear))
        })
        return info
    }

    private fun buildTspList(draft: CustomTrustedListDraft): TrustServiceProviderListType {
        val list = TrustServiceProviderListType()
        draft.trustServiceProviders.forEach { list.getTrustServiceProvider().add(buildTsp(it)) }
        return list
    }

    private fun buildTsp(tsp: TrustServiceProviderDraft): TSPType {
        val info = TSPInformationType()
        info.setTSPName(internationalNames(tsp.name))
        tsp.tradeName?.let { info.setTSPTradeName(internationalNames(it)) }
        info.setTSPAddress(buildAddress(requireNotNull(tsp.address)))
        info.setTSPInformationURI(multiLangUriList(tsp.infoUrl))

        val services = TSPServicesListType()
        tsp.services.forEach { services.getTSPService().add(buildService(it)) }

        val tspt = TSPType()
        tspt.setTSPInformation(info)
        tspt.setTSPServices(services)
        return tspt
    }

    private fun buildService(service: TrustServiceDraft): TSPServiceType {
        val now = xmlCalendarFactory.newXMLGregorianCalendar(GregorianCalendar())
        val svcInfo = TSPServiceInformationType()
        svcInfo.setServiceTypeIdentifier(service.typeIdentifier)
        svcInfo.setServiceName(internationalNames(service.name))
        svcInfo.setServiceDigitalIdentity(buildDigitalIdentity(service.certificatePath))
        svcInfo.setServiceStatus(service.status)
        svcInfo.setStatusStartingTime(now)

        val svc = TSPServiceType()
        svc.setServiceInformation(svcInfo)
        return svc
    }

    /**
     * Load a PEM or DER certificate from [certPath] and wrap it in a
     * [DigitalIdentityListType] containing one [DigitalIdentityType] with the raw DER bytes.
     */
    private fun buildDigitalIdentity(certPath: String): DigitalIdentityListType {
        val certBytes = File(certPath).inputStream().use { stream ->
            CertificateFactory.getInstance("X.509")
                .generateCertificate(stream)
                .encoded
        }
        val identity = DigitalIdentityType()
        identity.setX509Certificate(certBytes)
        val list = DigitalIdentityListType()
        list.getDigitalId().add(identity)
        return list
    }

    /**
     * Build the scheme operator's [AddressType] — one postal address plus one electronic address,
     * the minimum the schema accepts (ETSI TS 119612 clause 5.3.5).
     */
    private fun buildAddress(address: TrustedListAddress): AddressType {
        val postal = PostalAddressType()
        postal.setLang(DEFAULT_LANG)
        postal.setStreetAddress(address.streetAddress)
        postal.setLocality(address.locality)
        address.stateOrProvince?.takeIf { it.isNotBlank() }?.let { postal.setStateOrProvince(it) }
        address.postalCode?.takeIf { it.isNotBlank() }?.let { postal.setPostalCode(it) }
        postal.setCountryName(address.countryName)

        val postalList = PostalAddressListType()
        postalList.getPostalAddress().add(postal)

        val electronic = ElectronicAddressType()
        electronic.getURI().add(
            NonEmptyMultiLangURIType().apply {
                setLang(DEFAULT_LANG)
                setValue(address.electronicAddress)
            },
        )

        val result = AddressType()
        result.setPostalAddresses(postalList)
        result.setElectronicAddress(electronic)
        return result
    }

    private fun internationalNames(value: String): InternationalNamesType {
        val entry = MultiLangNormStringType()
        entry.setLang(DEFAULT_LANG)
        entry.setValue(value)
        val names = InternationalNamesType()
        names.getName().add(entry)
        return names
    }

    private fun multiLangUriList(uri: String): NonEmptyMultiLangURIListType {
        val entry = NonEmptyMultiLangURIType()
        entry.setLang(DEFAULT_LANG)
        entry.setValue(uri)
        val list = NonEmptyMultiLangURIListType()
        list.getURI().add(entry)
        return list
    }

    private companion object {
        const val ETSI_TL_VERSION = 5L
        const val TSL_TYPE_URI = "http://uri.etsi.org/TrstSvc/TrustedList/TSLType/EUgeneric"

        /**
         * The Trusted List Tag of ETSI TS 119612 clause 5.2, carried as the mandatory `TSLTag`
         * attribute on the root element. The schema types it as a bare `xsd:anyURI` and requires it
         * without constraining its value, so omitting it produces a document that marshalling
         * rejects outright rather than one that merely reads oddly.
         */
        const val TSL_TAG_URI = "http://uri.etsi.org/19612/TSLTag"
        const val DEFAULT_LANG = "en"
    }
}


