package cz.pizavo.omnisign.ui.model

/**
 * Editable state for a single trust service within a [TspEditState].
 *
 * @property name Human-readable name of the service.
 * @property typeIdentifier ETSI service type identifier URI.
 * @property status ETSI service status URI.
 * @property certificatePath Path to the PEM or DER certificate file.
 */
data class ServiceEditState(
	val name: String = "",
	val typeIdentifier: String = "",
	val status: String = "",
	val certificatePath: String = "",
)
