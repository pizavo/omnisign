package cz.pizavo.omnisign.ui.model

/**
 * Editable state for a single Trust Service Provider within the builder dialog.
 *
 * @property name Official name of the TSP.
 * @property tradeName Optional trade/brand name.
 * @property address Postal and electronic address of this provider, required by ETSI TS 119612
 *   clause 5.4.4.
 * @property infoUrl URL pointing to the TSP's information page. Required by clause 5.4.5.
 * @property services Services provided by this TSP.
 * @property expanded Whether the TSP card is currently expanded in the UI.
 */
data class TspEditState(
	val name: String = "",
	val tradeName: String = "",
	val address: AddressEditState = AddressEditState(),
	val infoUrl: String = "",
	val services: List<ServiceEditState> = emptyList(),
	val expanded: Boolean = true,
)
