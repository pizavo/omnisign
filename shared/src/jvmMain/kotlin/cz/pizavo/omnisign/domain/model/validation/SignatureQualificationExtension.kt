package cz.pizavo.omnisign.domain.model.validation

import eu.europa.esig.dss.enumerations.SignatureQualification

/**
 * Maps a DSS [SignatureQualification] to the domain [SignatureTrustTier].
 *
 * The mapping follows EU Regulation 910/2014 (eIDAS):
 *
 * | DSS qualification                        | Trust tier      | eIDAS basis              |
 * |------------------------------------------|-----------------|--------------------------|
 * | `QESIG`, `QESEAL`                        | QUALIFIED_QSCD  | Annex I + Annex III      |
 * | `NOT_ADES_QC_QSCD`, `UNKNOWN_QC_QSCD`   | QUALIFIED_QSCD  | QC + QSCD (non-AdES)     |
 * | `INDETERMINATE_QESIG`, `INDETERMINATE_QESEAL` | QUALIFIED_QSCD | QC + QSCD (indeterminate) |
 * | `INDETERMINATE_UNKNOWN_QC_QSCD`          | QUALIFIED_QSCD  | QC + QSCD (unknown type) |
 * | `ADESIG_QC`, `ADESEAL_QC`                | QUALIFIED       | Annex I, no QSCD         |
 * | `NOT_ADES_QC`, `UNKNOWN_QC`              | QUALIFIED       | QC (non-AdES)            |
 * | `INDETERMINATE_ADESIG_QC`, `INDETERMINATE_ADESEAL_QC` | QUALIFIED | QC (indeterminate) |
 * | `INDETERMINATE_UNKNOWN_QC`               | QUALIFIED       | QC (unknown type)        |
 * | Everything else (`ADESIG`, `NA`, …)      | NOT_QUALIFIED   | No qualified certificate |
 */
fun SignatureQualification.toTrustTier(): SignatureTrustTier = when (this) {
	SignatureQualification.QESIG,
	SignatureQualification.QESEAL,
	SignatureQualification.NOT_ADES_QC_QSCD,
	SignatureQualification.UNKNOWN_QC_QSCD,
	SignatureQualification.INDETERMINATE_QESIG,
	SignatureQualification.INDETERMINATE_QESEAL,
	SignatureQualification.INDETERMINATE_UNKNOWN_QC_QSCD,
	-> SignatureTrustTier.QUALIFIED_QSCD

	SignatureQualification.ADESIG_QC,
	SignatureQualification.ADESEAL_QC,
	SignatureQualification.NOT_ADES_QC,
	SignatureQualification.UNKNOWN_QC,
	SignatureQualification.INDETERMINATE_ADESIG_QC,
	SignatureQualification.INDETERMINATE_ADESEAL_QC,
	SignatureQualification.INDETERMINATE_UNKNOWN_QC,
	-> SignatureTrustTier.QUALIFIED

	SignatureQualification.ADESIG,
	SignatureQualification.ADESEAL,
	SignatureQualification.NOT_ADES,
	SignatureQualification.UNKNOWN,
	SignatureQualification.INDETERMINATE_ADESIG,
	SignatureQualification.INDETERMINATE_ADESEAL,
	SignatureQualification.INDETERMINATE_UNKNOWN,
	SignatureQualification.NA,
	-> SignatureTrustTier.NOT_QUALIFIED
}


