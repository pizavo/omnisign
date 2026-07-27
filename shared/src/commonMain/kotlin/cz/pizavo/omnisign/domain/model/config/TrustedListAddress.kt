package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * Postal and electronic address of a party named in a [CustomTrustedListDraft].
 *
 * ETSI TS 119612 demands both halves of this address twice over: once for the scheme operator
 * (clause 5.3.5) and once for every trust service provider listed (clause 5.4.4). The schema
 * enforces it, so a list missing either is rejected at marshalling rather than merely reading as
 * incomplete.
 *
 * None of it can be derived from the certificates being listed — it identifies organisations, not
 * keys — which is why it has to be supplied when the draft is authored.
 *
 * @property streetAddress Street address. Required.
 * @property locality Town or city. Required.
 * @property countryName Country, as a name or ISO 3166-1 code. Required.
 * @property stateOrProvince Optional state or province, for territories that use one.
 * @property postalCode Optional postal code.
 * @property electronicAddress A URI at which the party can be reached — typically a `mailto:` or an
 *   `https:` contact page. Required.
 */
@Serializable
data class TrustedListAddress(
    val streetAddress: String,
    val locality: String,
    val countryName: String,
    val stateOrProvince: String? = null,
    val postalCode: String? = null,
    val electronicAddress: String,
)
