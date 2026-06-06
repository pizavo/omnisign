package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.parameters.RawReportFormat

/**
 * Supported formats for exporting a [ValidationReport] to a file.
 *
 * Each entry carries a human-readable [label], a short [description] explaining
 * the content, a conventional [extension] for the saved file, and a [fileStem]
 * identifying the report kind within a generated file name.
 *
 * @property label Short display label shown in UI pickers.
 * @property description Concise explanation of what the format contains.
 * @property extension Conventional file extension (without a leading dot).
 * @property fileStem Report-kind segment used in default file names, e.g. `report`
 *   for [TXT]/[JSON] or `simple` for [XML_SIMPLE]. Keeps the four XML variants distinct.
 * @property rawReportFormat When non-null, exporting in this format requires the
 *   corresponding raw DSS report stored in [ValidationReport.rawReports].
 */
enum class ReportExportFormat(
    val label: String,
    val description: String,
    val extension: String,
    val fileStem: String,
    val rawReportFormat: RawReportFormat? = null,
) {
    /**
     * Plain-text human-readable summary generated from the domain model.
     */
    TXT(
        label = "Plain Text",
        description = "Human-readable summary with signature details, timestamps, and warnings.",
        extension = "txt",
        fileStem = "report",
    ),

    /**
     * Structured JSON representation of the domain [ValidationReport].
     */
    JSON(
        label = "JSON",
        description = "Machine-readable JSON with signatures, certificates, timestamps, and a summary.",
        extension = "json",
        fileStem = "report",
    ),

    /**
     * ETSI EN 319 102-1 detailed report XML produced by the DSS library.
     */
    XML_DETAILED(
        label = "XML — Detailed Report",
        description = "ETSI EN 319 102-1 detailed report with per-check building-block results.",
        extension = "xml",
        fileStem = "detailed",
        rawReportFormat = RawReportFormat.XML_DETAILED,
    ),

    /**
     * DSS simple report XML — a concise summary suitable for human-readable tooling.
     */
    XML_SIMPLE(
        label = "XML — Simple Report",
        description = "DSS simple report — concise per-signature summary in XML.",
        extension = "xml",
        fileStem = "simple",
        rawReportFormat = RawReportFormat.XML_SIMPLE,
    ),

    /**
     * DSS diagnostic data XML — full low-level cryptographic evidence.
     */
    XML_DIAGNOSTIC(
        label = "XML — Diagnostic Data",
        description = "Full low-level cryptographic evidence (certificates, revocation data, timestamps).",
        extension = "xml",
        fileStem = "diagnostic",
        rawReportFormat = RawReportFormat.XML_DIAGNOSTIC,
    ),

    /**
     * ETSI TS 119 102-2 signature validation report (SVR) XML.
     */
    XML_ETSI(
        label = "XML — ETSI Validation Report",
        description = "ETSI TS 119 102-2 SVR — standardised interoperable validation report.",
        extension = "xml",
        fileStem = "etsi",
        rawReportFormat = RawReportFormat.XML_ETSI,
    ),
    ;

    /**
     * Build the default save name (without the trailing [extension]) for a report exported
     * from the document named [documentName]. The source name's directory and extension are
     * dropped and the [fileStem] is appended, so "contract.pdf" with [XML_SIMPLE] yields
     * "contract.simple". A blank source name falls back to "validation-report".
     */
    fun suggestedBaseName(documentName: String): String {
        val fileName = documentName.substringAfterLast('/').substringAfterLast('\\')
        val base = fileName.substringBeforeLast('.', fileName).trim().ifBlank { "validation-report" }
        return "$base.$fileStem"
    }
}
