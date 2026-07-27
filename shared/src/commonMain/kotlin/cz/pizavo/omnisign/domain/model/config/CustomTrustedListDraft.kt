package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * In-progress trusted list definition built interactively through the CLI builder.
 *
 * A draft collects [TrustServiceProviderDraft] entries and is eventually compiled
 * into a standards-conformant ETSI TS 119612 XML document by the platform-specific
 * `TrustedListCompiler`.
 */
@Serializable
data class CustomTrustedListDraft(
    /**
     * Unique name identifying this draft. Also used as the default output file stem.
     */
    val name: String,

    /**
     * ISO 3166-1 alpha-2 territory code for the scheme territory (e.g. `CZ`, `SK`).
     */
    val territory: String = "XX",

    /**
     * Name of the scheme operator (the entity publishing this TL).
     */
    val schemeOperatorName: String = "",

    /**
     * Postal and electronic address of the scheme operator.
     *
     * Mandatory for compilation (ETSI TS 119612 clause 5.3.5) but nullable here, because a draft is
     * an in-progress definition that may not have collected it yet; the compiler reports it as a
     * missing field rather than failing on a schema violation.
     */
    val schemeOperatorAddress: TrustedListAddress? = null,

    /**
     * Name of the scheme this list represents (clause 5.3.6) — e.g. `"Internal PKI trust anchors"`.
     * Distinct from [name], which identifies the draft locally.
     */
    val schemeName: String = "",

    /**
     * URI where information about the scheme is published (clause 5.3.7).
     */
    val schemeInformationUri: String = "",

    /**
     * URI naming how the operator determines the status of the services it lists (clause 5.3.9).
     * Defaults to the generic ETSI approach, which is the appropriate one for a list published
     * outside an EU member-state supervisory scheme.
     */
    val statusDeterminationApproach: String = STATUS_DETERMINATION_APPROPRIATE,

    /**
     * Number of days of service history the list retains (clause 5.3.12). Defaults to the value EU
     * trusted lists use to mean "the entire history".
     */
    val historicalInformationPeriod: Int = DEFAULT_HISTORICAL_INFORMATION_PERIOD_DAYS,

    /**
     * Trust service providers listed in this trusted list.
     */
    val trustServiceProviders: List<TrustServiceProviderDraft> = emptyList()
)

/**
 * Status-determination approach for a list published outside an EU member-state supervisory scheme
 * (ETSI TS 119612 clause 5.3.9). The EU-specific approaches assert supervision this list does not
 * carry, so the generic one is the honest default for a self-published trust anchor list.
 */
const val STATUS_DETERMINATION_APPROPRIATE: String =
    "http://uri.etsi.org/TrstSvc/TrustedList/StatusDetn/appropriate"

/**
 * Default retention period, in days, advertised as the list's historical information period
 * (ETSI TS 119612 clause 5.3.12). Matches the value EU trusted lists carry.
 */
const val DEFAULT_HISTORICAL_INFORMATION_PERIOD_DAYS: Int = 65535

/**
 * Draft representation of a Trust Service Provider (TSP) within a [CustomTrustedListDraft].
 */
@Serializable
data class TrustServiceProviderDraft(
    /**
     * Official name of the TSP.
     */
    val name: String,

    /**
     * Optional trade/brand name of the TSP.
     */
    val tradeName: String? = null,

    /**
     * Postal and electronic address of this provider.
     *
     * Mandatory for compilation (ETSI TS 119612 clause 5.4.4), nullable here for the same reason
     * [CustomTrustedListDraft.schemeOperatorAddress] is: a draft may be mid-authoring.
     */
    val address: TrustedListAddress? = null,

    /**
     * URL pointing to the TSP's information page or registration. Mandatory for compilation
     * (clause 5.4.5) — the schema has no way to express "this provider publishes nothing".
     */
    val infoUrl: String = "",

    /**
     * Individual trust services provided by this TSP.
     */
    val services: List<TrustServiceDraft> = emptyList()
)

/**
 * Draft representation of a single trust service within a [TrustServiceProviderDraft].
 */
@Serializable
data class TrustServiceDraft(
    /**
     * Human-readable name of the service.
     */
    val name: String,

    /**
     * Service type identifier URI (e.g. `http://uri.etsi.org/TrstSvc/Svctype/CA/QC`).
     */
    val typeIdentifier: String,

    /**
     * Service status URI (e.g. `http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted`).
     */
    val status: String,

    /**
     * Path to the PEM or DER certificate file that represents the service's digital identity.
     */
    val certificatePath: String
)

