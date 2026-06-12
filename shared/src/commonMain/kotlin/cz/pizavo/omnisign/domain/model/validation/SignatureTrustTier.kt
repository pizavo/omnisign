package cz.pizavo.omnisign.domain.model.validation

import kotlinx.serialization.Serializable

/**
 * Trust tier classification for a validated signature based on its eIDAS qualification.
 *
 * The tier is derived from the DSS `SignatureQualification` and reflects
 * EU Regulation 910/2014 (eIDAS):
 *
 * - [QUALIFIED_QSCD] — the signing certificate is a qualified certificate (Annex I),
 *   **and** the signature was created on a Qualified Signature/Seal Creation Device (Annex III).
 * - [QUALIFIED] — the signing certificate is a qualified certificate (Annex I), but
 *   the QSCD status could not be confirmed.
 * - [NOT_QUALIFIED] — the certificate is not qualified, or qualification could not be determined.
 *
 * This classification is independent of the trusted list source — it applies equally to
 * certificates found on the EU LOTL and on custom ETSI trusted lists.
 *
 * @property label Human-readable label for display in UIs and reports.
 */
@Serializable
enum class SignatureTrustTier(val label: String) {
	/** Qualified certificate on a QSCD (eIDAS Annex I and Annex III). */
	QUALIFIED_QSCD("Qualified"),

	/** Qualified certificate without confirmed QSCD (eIDAS Annex I). */
	QUALIFIED("Recognized"),

	/** Not qualified or qualification could not be determined. */
	NOT_QUALIFIED("Not qualified"),
}

/**
 * A one-line confirmation — for the qualification-info surface — that the signing key resides in a
 * qualified signature creation device. Returned only for [QUALIFIED_QSCD], the tier DSS assigns when
 * the QSCD condition holds at both the certificate's issuance time and the best-signature time; it is
 * the positive inverse of DSS's two "private key does not reside in a QSCD …" qualification warnings,
 * and (since either failing check drops the `_QSCD` qualification) cannot coexist with them. `null`
 * for the other tiers, where no such confirmation applies.
 */
fun SignatureTrustTier.qscdResidenceInfo(): String? = when (this) {
	SignatureTrustTier.QUALIFIED_QSCD ->
		"The private key resides in a QSCD at both issuance and signing time."
	SignatureTrustTier.QUALIFIED, SignatureTrustTier.NOT_QUALIFIED -> null
}

