package cz.pizavo.omnisign.ui.layout

import cz.pizavo.omnisign.domain.model.value.commonNameOf
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo

/**
 * The human-readable common name (the `CN=` RDN) of this certificate's subject DN, for
 * display in the certificate dropdown.
 *
 * Display-only: the certificate's identity for selection and signing-key resolution stays
 * the full unique [AvailableCertificateInfo.alias] (`CN-<serialHex>@<sourceId>`), so two
 * certificates that share a subject CN (a renewal overlap, or the same cert on two
 * sources) remain unambiguously resolvable even though they show the same name here — the
 * row disambiguates them visually by validity and source instead.
 *
 * Delegates extraction to [commonNameOf] — the single CN-from-DN implementation shared
 * with the signing-critical alias derivation, so display and identity always agree.  When
 * the subject has no `CN=` RDN, or an empty one, the full subject DN is returned so the
 * label is never blank.
 *
 * @return The subject common name, or the full subject DN when no usable `CN=` is present.
 */
internal fun AvailableCertificateInfo.commonName(): String =
	commonNameOf(subjectDN)?.takeIf { it.isNotEmpty() } ?: subjectDN
