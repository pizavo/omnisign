package cz.pizavo.omnisign.domain.model.config.enums

import eu.europa.esig.dss.enumerations.SignatureLevel as DssSignatureLevel

/**
 * Maps the domain [SignatureLevel] to the corresponding DSS [DssSignatureLevel] enum constant.
 *
 * A direct name-based conversion (e.g. `Enum.valueOf`) cannot be used because the domain
 * enum uses `PADES_BASELINE_B` while DSS uses `PAdES_BASELINE_B`.
 */
fun SignatureLevel.toDss(): DssSignatureLevel = when (this) {
    SignatureLevel.PADES_BASELINE_B -> DssSignatureLevel.PAdES_BASELINE_B
    SignatureLevel.PADES_BASELINE_T -> DssSignatureLevel.PAdES_BASELINE_T
    SignatureLevel.PADES_BASELINE_LT -> DssSignatureLevel.PAdES_BASELINE_LT
    SignatureLevel.PADES_BASELINE_LTA -> DssSignatureLevel.PAdES_BASELINE_LTA
}

/**
 * Maps a DSS [DssSignatureLevel] back to the domain [SignatureLevel], or `null` when it is not one
 * of the four PAdES baseline levels the application works with.
 *
 * DSS reports more levels than the baseline profile defines — the legacy PAdES forms (`PAdES_BES`,
 * `PAdES_EPES`, `PAdES_LTV`), the PKCS#7 forms, and the CAdES/XAdES families. A document whose
 * signature falls outside the baseline profile has no domain level to name, so the caller decides
 * what to do with `null` rather than being handed a misleading approximation.
 */
fun DssSignatureLevel.toDomainOrNull(): SignatureLevel? = when (this) {
    DssSignatureLevel.PAdES_BASELINE_B -> SignatureLevel.PADES_BASELINE_B
    DssSignatureLevel.PAdES_BASELINE_T -> SignatureLevel.PADES_BASELINE_T
    DssSignatureLevel.PAdES_BASELINE_LT -> SignatureLevel.PADES_BASELINE_LT
    DssSignatureLevel.PAdES_BASELINE_LTA -> SignatureLevel.PADES_BASELINE_LTA
    else -> null
}


