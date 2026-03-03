package cz.pizavo.omnisign.domain.model.config.enums

import eu.europa.esig.dss.enumerations.EncryptionAlgorithm as DssEncryptionAlgorithm

/**
 * Maps the domain [EncryptionAlgorithm] to the corresponding DSS
 * [eu.europa.esig.dss.enumerations.EncryptionAlgorithm] constant using the
 * DSS [DssEncryptionAlgorithm.forName] lookup so future DSS upgrades remain
 * compatible without changing this mapping.
 */
fun EncryptionAlgorithm.toDss(): DssEncryptionAlgorithm =
	DssEncryptionAlgorithm.forName(dssName)

