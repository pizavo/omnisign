package cz.pizavo.omnisign.domain.model.validation

/**
 * Trust tier classification for a validated signature based on its eIDAS qualification.
 *
 * The tier is derived from the DSS `SignatureQualification` and reflects
 * EU Regulation 910/2014 (eIDAS):
 *
 * - [QUALIFIED_QSCD] — the signing certificate is a qualified certificate (Annex I)
 *   **and** the signature was created on a Qualified Signature/Seal Creation Device (Annex III).
 * - [QUALIFIED] — the signing certificate is a qualified certificate (Annex I) but
 *   the QSCD status could not be confirmed.
 * - [NOT_QUALIFIED] — the certificate is not qualified, or qualification could not be determined.
 *
 * This classification is independent of the trusted list source — it applies equally to
 * certificates found on the EU LOTL and on custom ETSI trusted lists.
 *
 * @property label Human-readable label for display in UIs and reports.
 */
enum class SignatureTrustTier(val label: String) {
	/** Qualified certificate on a QSCD (eIDAS Annex I + Annex III). */
	QUALIFIED_QSCD("Qualified (QSCD)"),

	/** Qualified certificate without confirmed QSCD (eIDAS Annex I). */
	QUALIFIED("Qualified"),

	/** Not qualified or qualification could not be determined. */
	NOT_QUALIFIED("Not qualified"),
}

