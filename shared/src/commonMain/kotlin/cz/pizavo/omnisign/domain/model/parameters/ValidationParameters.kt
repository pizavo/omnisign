package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig

/**
 * Format of the raw DSS validation report to write to disk.
 */
enum class RawReportFormat {
	/** ETSI EN 319 102-1 detailed report XML as produced by the DSS library. */
	XML_DETAILED,
	
	/** DSS simple report XML — a concise summary suitable for human-readable tooling. */
	XML_SIMPLE,
	
	/** DSS diagnostic data XML — full low-level cryptographic evidence. */
	XML_DIAGNOSTIC,
	
	/** ETSI EN 319 102-1 SVR (signature validation report) as specified in ETSI TS 119 102-2. */
	XML_ETSI,
}

/**
 * Parameters for a document validation operation.
 *
 * @property inputFile Absolute path to the PDF to validate.
 * @property customPolicyPath Optional path to a custom ETSI validation policy XML file.
 * @property resolvedConfig Pre-resolved configuration; repository falls back to the active
 *   config when null.
 * @property rawReportOutputPath When non-null the repository writes the native DSS report
 *   to this path in addition to returning the domain [cz.pizavo.omnisign.domain.model.validation.ValidationReport].
 * @property rawReportFormat Format of the raw report to write; ignored when
 *   [rawReportOutputPath] is null. Defaults to [RawReportFormat.XML_DETAILED].
 */
data class ValidationParameters(
	val inputFile: String,
	val customPolicyPath: String? = null,
	val resolvedConfig: ResolvedConfig? = null,
	val rawReportOutputPath: String? = null,
	val rawReportFormat: RawReportFormat = RawReportFormat.XML_DETAILED,
)

