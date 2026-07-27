package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.TrustedListAddress

/**
 * Editable postal and electronic address within the Trusted List builder dialog.
 *
 * Mirrors [cz.pizavo.omnisign.domain.model.config.TrustedListAddress] as flat strings, because the
 * form holds partially typed input that need not yet be a valid address. ETSI TS 119612 requires one
 * of these for the scheme operator and one for every trust service provider, so the same state backs
 * both places in the dialog.
 *
 * @property street Street address. Required to compile.
 * @property locality Town or city. Required to compile.
 * @property country Country name or ISO 3166-1 code. Required to compile.
 * @property stateOrProvince Optional state or province.
 * @property postalCode Optional postal code.
 * @property electronicAddress Contact URI. Required to compile.
 */
data class AddressEditState(
	val street: String = "",
	val locality: String = "",
	val country: String = "",
	val stateOrProvince: String = "",
	val postalCode: String = "",
	val electronicAddress: String = "",
)

/**
 * Whether every part ETSI TS 119612 requires has been filled in.
 *
 * State and postal code are excluded deliberately — the schema marks both optional, and demanding
 * them would block territories that do not use one.
 */
fun AddressEditState.isComplete(): Boolean =
	street.isNotBlank() && locality.isNotBlank() && country.isNotBlank() && electronicAddress.isNotBlank()

/**
 * Convert to the domain [TrustedListAddress], trimming each part and dropping the optional ones when
 * blank.
 */
fun AddressEditState.toAddress(): TrustedListAddress = TrustedListAddress(
	streetAddress = street.trim(),
	locality = locality.trim(),
	countryName = country.trim(),
	stateOrProvince = stateOrProvince.trim().ifBlank { null },
	postalCode = postalCode.trim().ifBlank { null },
	electronicAddress = electronicAddress.trim(),
)
