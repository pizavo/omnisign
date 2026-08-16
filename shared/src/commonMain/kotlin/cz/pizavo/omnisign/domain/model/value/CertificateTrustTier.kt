package cz.pizavo.omnisign.domain.model.value

/**
 * Trust tier of a certificate offered for signing, derived from the QCStatements X.509
 * extension (OID `1.3.6.1.5.5.7.1.3`) the issuer placed in the certificate itself.
 *
 * - [QUALIFIED_QSCD] — the certificate asserts `id-etsi-qcs-QcSSCD` (OID `0.4.0.1862.1.4`),
 *   so the private key is held in a qualified signature creation device.
 * - [QUALIFIED] — the certificate asserts `id-etsi-qcs-QcCompliance` (OID `0.4.0.1862.1.1`)
 *   without `QcSSCD`, so it is a qualified certificate whose QSCD status is unconfirmed.
 * - [NOT_QUALIFIED] — a QCStatements extension is present but asserts neither statement.
 * - [UNKNOWN] — no QCStatements extension is present, or it could not be parsed.
 *
 * [NOT_QUALIFIED] and [UNKNOWN] are deliberately **distinct for display but equal for
 * ordering** (see [sortRank]): a certificate must not rank above another merely for
 * carrying an extension that asserts nothing about it, yet the two cases must stay
 * distinguishable so a listing can stay silent about a certificate that never claimed
 * anything rather than labelling it "not qualified".
 *
 * This is the issuer's assertion read locally off the certificate, which is **not** the
 * authoritative eIDAS determination: that one combines these statements with the trusted
 * list's service qualifiers (`QCWithSSCD`, `QCSSCDStatusAsInCert`) and is modelled
 * separately by [cz.pizavo.omnisign.domain.model.validation.SignatureTrustTier], computed
 * by DSS during validation.  The two can disagree — this tier is the cheap local view used
 * to order and label a selection list, the validation report is the verdict.
 *
 * @property sortRank Rank used to order certificates by qualification, lowest first.
 *   Shared by [NOT_QUALIFIED] and [UNKNOWN] so they tie and fall through to the next
 *   ordering key.
 */
enum class CertificateTrustTier(val sortRank: Int) {
	/** Qualified certificate whose key resides in a QSCD (`QcSSCD` asserted). */
	QUALIFIED_QSCD(0),

	/** Qualified certificate without confirmed QSCD (`QcCompliance` asserted, `QcSSCD` absent). */
	QUALIFIED(1),

	/** QCStatements present, asserting neither `QcCompliance` nor `QcSSCD`. */
	NOT_QUALIFIED(2),

	/** No QCStatements extension, or the extension was unreadable. */
	UNKNOWN(2),
	;

	companion object {
		/**
		 * Classify a certificate from the two QCStatements flags carried on the domain models.
		 *
		 * [isQscd] wins over [isQualified]: a certificate asserting `QcSSCD` is treated as
		 * qualified on a QSCD regardless of how `QcCompliance` reads, matching the precedence
		 * the listing surfaces already applied inline.
		 *
		 * @param isQualified Whether `QcCompliance` is asserted; `null` when the extension is
		 *   absent or unreadable.
		 * @param isQscd Whether `QcSSCD` is asserted; `null` when the extension is absent or
		 *   unreadable.
		 * @return The matching tier, never `null`.
		 */
		fun of(isQualified: Boolean?, isQscd: Boolean?): CertificateTrustTier = when {
			isQscd == true -> QUALIFIED_QSCD
			isQualified == true -> QUALIFIED
			isQualified == false -> NOT_QUALIFIED
			else -> UNKNOWN
		}
	}
}
