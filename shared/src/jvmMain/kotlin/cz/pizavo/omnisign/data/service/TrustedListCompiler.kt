package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.domain.model.config.CustomTrustedListDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceDraft
import cz.pizavo.omnisign.domain.model.config.TrustServiceProviderDraft
import eu.europa.esig.trustedlist.TrustedListFacade
import eu.europa.esig.trustedlist.jaxb.tsl.DigitalIdentityListType
import eu.europa.esig.trustedlist.jaxb.tsl.DigitalIdentityType
import eu.europa.esig.trustedlist.jaxb.tsl.InternationalNamesType
import eu.europa.esig.trustedlist.jaxb.tsl.MultiLangNormStringType
import eu.europa.esig.trustedlist.jaxb.tsl.NextUpdateType
import eu.europa.esig.trustedlist.jaxb.tsl.NonEmptyMultiLangURIListType
import eu.europa.esig.trustedlist.jaxb.tsl.NonEmptyMultiLangURIType
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
    fun compile(draft: CustomTrustedListDraft): String =
        try {
            TrustedListFacade.newFacade().marshall(buildTrustStatusList(draft))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to compile trusted list '${draft.name}': ${e.message}", e)
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
        tsl.setSchemeInformation(buildSchemeInformation(draft))
        tsl.setTrustServiceProviderList(buildTspList(draft))
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
        if (tsp.infoUrl.isNotBlank()) {
            info.setTSPInformationURI(multiLangUriList(tsp.infoUrl))
        }

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
        const val DEFAULT_LANG = "en"
    }
}


