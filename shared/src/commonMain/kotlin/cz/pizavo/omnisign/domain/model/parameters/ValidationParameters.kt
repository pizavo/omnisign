package cz.pizavo.omnisign.domain.model.parameters

import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import kotlinx.serialization.Serializable

/**
 * Format of the raw DSS validation report to write to disk.
 */
@Serializable
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
 * The document is always carried as in-memory bytes so the same parameter shape works for
 * the JVM in-process validator (which wraps them in a DSS `InMemoryDocument`) and for the
 * web target's `RemoteValidationRepository` (which POSTs them as a `multipart/form-data`
 * `file` field). Configuration is described two ways so each impl can pick what it needs:
 * the JVM in-process flow passes a pre-resolved [resolvedConfig], whereas the web flow
 * leaves [resolvedConfig] null and instead sends the [profileName] plus per-request
 * [disabledHashAlgorithms] / [disabledEncryptionAlgorithms] overrides so the server can
 * resolve against its own configuration.
 *
 * @property inputBytes Raw PDF bytes to validate.
 * @property inputName File name attached to the document (used as the DSS document name on
 *   JVM and as the multipart `filename=` on web).
 * @property customPolicyPath Optional path to a custom ETSI validation policy XML file. JVM-only;
 *   ignored by the web target.
 * @property resolvedConfig Pre-resolved configuration for the JVM in-process flow; repository
 *   falls back to the active config when null. The web target ignores this field and resolves
 *   server-side from [profileName] and the per-request override sets below.
 * @property profileName Optional name of a server-side configuration profile to apply. Used by
 *   the web target's `RemoteValidationRepository` to drive server-side resolution; JVM impls
 *   ignore it when [resolvedConfig] is already populated.
 * @property disabledHashAlgorithms Per-request strictly-tightening overrides for the hash
 *   algorithms the operation refuses. Sent as the `disableHashAlgorithm` multipart field by the
 *   web target; merged into the resolved config server-side.
 * @property disabledEncryptionAlgorithms Per-request strictly-tightening overrides for the
 *   encryption algorithms the operation refuses. Sent as the `disableEncryptionAlgorithm`
 *   multipart field by the web target.
 * @property rawReportOutputPath When non-null the repository writes the native DSS report
 *   to this path in addition to returning the domain [cz.pizavo.omnisign.domain.model.validation.ValidationReport].
 *   JVM-only; the web target ignores this field.
 * @property rawReportFormat Format of the raw report to write; ignored when
 *   [rawReportOutputPath] is null. Defaults to [RawReportFormat.XML_DETAILED]. JVM-only.
 * @property rawReportFormats Raw DSS report formats to marshal into
 *   [cz.pizavo.omnisign.domain.model.validation.ValidationReport.rawReports]. Each requested
 *   format triggers a separate (potentially expensive) JAXB marshalling pass, so the default is
 *   empty and callers opt in. The desktop UI requests the full set so the user can export any
 *   format on demand without re-validating. The CLI leaves it empty (it writes a single format
 *   directly via [rawReportOutputPath]). The web target forwards the set to the server as the
 *   `formats` multipart field. Independent of [rawReportOutputPath], which writes a single
 *   format straight to disk.
 */
data class ValidationParameters(
	val inputBytes: ByteArray,
	val inputName: String,
	val customPolicyPath: String? = null,
	val resolvedConfig: ResolvedConfig? = null,
	val profileName: String? = null,
	val disabledHashAlgorithms: Set<HashAlgorithm> = emptySet(),
	val disabledEncryptionAlgorithms: Set<EncryptionAlgorithm> = emptySet(),
	val rawReportOutputPath: String? = null,
	val rawReportFormat: RawReportFormat = RawReportFormat.XML_DETAILED,
	val rawReportFormats: Set<RawReportFormat> = emptySet(),
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is ValidationParameters) return false
		return inputName == other.inputName &&
			inputBytes.contentEquals(other.inputBytes) &&
			customPolicyPath == other.customPolicyPath &&
			resolvedConfig == other.resolvedConfig &&
			profileName == other.profileName &&
			disabledHashAlgorithms == other.disabledHashAlgorithms &&
			disabledEncryptionAlgorithms == other.disabledEncryptionAlgorithms &&
			rawReportOutputPath == other.rawReportOutputPath &&
			rawReportFormat == other.rawReportFormat &&
			rawReportFormats == other.rawReportFormats
	}

	override fun hashCode(): Int {
		var result = inputBytes.contentHashCode()
		result = 31 * result + inputName.hashCode()
		result = 31 * result + (customPolicyPath?.hashCode() ?: 0)
		result = 31 * result + (resolvedConfig?.hashCode() ?: 0)
		result = 31 * result + (profileName?.hashCode() ?: 0)
		result = 31 * result + disabledHashAlgorithms.hashCode()
		result = 31 * result + disabledEncryptionAlgorithms.hashCode()
		result = 31 * result + (rawReportOutputPath?.hashCode() ?: 0)
		result = 31 * result + rawReportFormat.hashCode()
		result = 31 * result + rawReportFormats.hashCode()
		return result
	}
}

