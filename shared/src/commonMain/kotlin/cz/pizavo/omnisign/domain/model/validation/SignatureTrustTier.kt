package cz.pizavo.omnisign.domain.model.validation

import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
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
 */
@Serializable
enum class SignatureTrustTier {
	/** Qualified certificate on a QSCD (eIDAS Annex I and Annex III). */
	QUALIFIED_QSCD,

	/** Qualified certificate without confirmed QSCD (eIDAS Annex I). */
	QUALIFIED,

	/** Not qualified or qualification could not be determined. */
	NOT_QUALIFIED,
}

/**
 * Localizable display label for this tier — `Qualified` / `Recognized` / `Not qualified`. The
 * certificate-details panel resolves it to the active locale; the plain-text report renders its
 * [LocalizableText.english].
 */
fun SignatureTrustTier.label(): LocalizableText = when (this) {
	SignatureTrustTier.QUALIFIED_QSCD -> LocalizableText.of(MessageKey.TRUST_TIER_QUALIFIED)
	SignatureTrustTier.QUALIFIED -> LocalizableText.of(MessageKey.TRUST_TIER_RECOGNIZED)
	SignatureTrustTier.NOT_QUALIFIED -> LocalizableText.of(MessageKey.TRUST_TIER_NOT_QUALIFIED)
}

/**
 * A one-line confirmation — for the qualification-info surface — that the signing key resides in a
 * qualified signature creation device. Returned only for [QUALIFIED_QSCD], the tier DSS assigns when
 * the QSCD condition holds at both the certificate's issuance time and the best-signature time; it is
 * the positive inverse of DSS's two "private key does not reside in a QSCD …" qualification warnings,
 * and (since either failing check drops the `_QSCD` qualification) cannot coexist with them. `null`
 * for the other tiers, where no such confirmation applies.
 */
fun SignatureTrustTier.qscdResidenceInfo(): LocalizableText? = when (this) {
	SignatureTrustTier.QUALIFIED_QSCD -> LocalizableText.of(MessageKey.SIGNATURE_QSCD_RESIDENCE)
	SignatureTrustTier.QUALIFIED, SignatureTrustTier.NOT_QUALIFIED -> null
}

