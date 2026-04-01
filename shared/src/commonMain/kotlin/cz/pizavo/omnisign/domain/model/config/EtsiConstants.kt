package cz.pizavo.omnisign.domain.model.config

/**
 * Well-known ETSI TS 119612 service type identifier URIs and service status URIs.
 *
 * These constants are used both in the CLI builder wizard and in the Compose
 * TL builder dialog to offer human-readable dropdown hints.
 *
 * @property uri The full ETSI URI.
 * @property label A short human-readable description.
 */
data class EtsiUriHint(val uri: String, val label: String)

/**
 * Standard service type identifiers defined by ETSI TS 119612.
 */
val SERVICE_TYPE_HINTS: List<EtsiUriHint> = listOf(
	EtsiUriHint("http://uri.etsi.org/TrstSvc/Svctype/CA/QC", "CA/QC — Qualified CA"),
	EtsiUriHint("http://uri.etsi.org/TrstSvc/Svctype/CA/PKC", "CA/PKC — Non-qualified CA"),
	EtsiUriHint("http://uri.etsi.org/TrstSvc/Svctype/TSA/QTST", "TSA/QTST — Qualified timestamp authority"),
	EtsiUriHint("http://uri.etsi.org/TrstSvc/Svctype/TSA", "TSA — Non-qualified timestamp authority"),
	EtsiUriHint("http://uri.etsi.org/TrstSvc/Svctype/EDS/Q", "EDS/Q — Qualified electronic delivery"),
	EtsiUriHint("http://uri.etsi.org/TrstSvc/Svctype/OCSP/QC", "OCSP/QC — Qualified OCSP"),
)

/**
 * Standard service status URIs defined by ETSI TS 119612.
 */
val SERVICE_STATUS_HINTS: List<EtsiUriHint> = listOf(
	EtsiUriHint("http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted", "Granted — Active / granted"),
	EtsiUriHint("http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn", "Withdrawn"),
	EtsiUriHint(
		"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/recognisedatnationallevel",
		"Recognised at national level",
	),
	EtsiUriHint(
		"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/deprecatedatnationallevel",
		"Deprecated at national level",
	),
)

