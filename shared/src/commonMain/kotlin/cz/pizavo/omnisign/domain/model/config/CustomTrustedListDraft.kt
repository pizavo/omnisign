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
     * Trust service providers listed in this trusted list.
     */
    val trustServiceProviders: List<TrustServiceProviderDraft> = emptyList()
)

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
     * URL pointing to the TSP's information page or registration.
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

