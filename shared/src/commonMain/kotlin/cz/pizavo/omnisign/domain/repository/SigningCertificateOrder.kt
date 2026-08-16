package cz.pizavo.omnisign.domain.repository

import cz.pizavo.omnisign.domain.model.value.CertificateTrustTier
import cz.pizavo.omnisign.domain.model.value.commonNameOf

/**
 * Order in which certificates are offered to the user for signing, most useful first.
 *
 * 1. **Qualification**, by [CertificateTrustTier.sortRank] — QSCD-backed qualified
 *    certificates, then qualified ones without confirmed QSCD, then the rest.  Ordering
 *    rather than filtering: a certificate outside the top tier still produces a valid
 *    signature, so nothing is hidden, the heaviest option merely surfaces first.
 * 2. **Expiry, latest first** — a renewal outranks the certificate it replaces, and an
 *    already-expired certificate sinks to the bottom of its tier without needing a filter
 *    (validity is not part of the signing-capability test, so expired certificates do
 *    reach this list).
 * 3. **Displayed common name, ascending** — the same string the selection UI renders, via
 *    the shared [commonNameOf], so the order the user reads matches the order applied.
 *
 * The name comparison is codepoint-ordered rather than locale-collated, which misplaces a
 * name only when an accented character is the first point of divergence between two
 * otherwise identical prefixes (`Pzuk` before `Píža`).  Locale collation would require a
 * JVM-only `Collator` and so could not live in `commonMain` alongside the rest of the
 * ordering.
 */
val signingCertificateOrder: Comparator<AvailableCertificateInfo> =
	compareBy<AvailableCertificateInfo> { CertificateTrustTier.of(it.isQualified, it.isQscd).sortRank }
		.thenByDescending { it.validTo }
		.thenBy { commonNameOf(it.subjectDN)?.takeIf { name -> name.isNotEmpty() } ?: it.subjectDN }
