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

/**
 * Maps this DSS [eu.europa.esig.dss.enumerations.EncryptionAlgorithm] back to the domain
 * [EncryptionAlgorithm] that [toDss] maps to it, or returns `null` for one OmniSign does not sign
 * with, such as the key-agreement algorithms X25519 and X448.
 */
fun DssEncryptionAlgorithm.toDomainOrNull(): EncryptionAlgorithm? =
	EncryptionAlgorithm.entries.firstOrNull { it.toDss() == this }

