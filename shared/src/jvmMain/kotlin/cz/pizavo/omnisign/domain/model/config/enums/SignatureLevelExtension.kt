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


