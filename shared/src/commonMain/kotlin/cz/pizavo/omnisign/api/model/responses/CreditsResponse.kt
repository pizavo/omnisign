package cz.pizavo.omnisign.api.model.responses

import cz.pizavo.omnisign.branding.PRODUCT_NAME
import cz.pizavo.omnisign.legal.OMNISIGN_LICENSE_ID
import cz.pizavo.omnisign.legal.OMNISIGN_SOURCE_URL
import cz.pizavo.omnisign.legal.THIRD_PARTY_NOTICES_URL
import cz.pizavo.omnisign.legal.ThirdPartyComponent
import kotlinx.serialization.Serializable

/**
 * Response for `GET /api/v1/credits` listing the third-party components the running server
 * distributes, alongside OmniSign's own licence and source location.
 *
 * The desktop and web apps show the same list in a dialog, but a server has no interface in
 * which to show anything. This endpoint is its equivalent: it lets a user interacting with the
 * deployment over the network — or an auditor with nothing but the base URL — obtain the
 * attributions the weak-copyleft licences require to accompany the work, and the offer of source
 * the GNU AGPL requires to be extended to a network user. It is therefore always public, exactly
 * like the health and capability probes; gating it behind authentication would defeat its point.
 *
 * The CLI's `credits --json` emits this same document, so one documented shape covers both
 * headless surfaces.
 *
 * Lives in `shared/commonMain` so the server (producing the response) and any client
 * (deserializing it) reference the exact same type.
 *
 * @property components Every third-party component this package ships, each with its licence,
 *   copyright and source location. Filtered to the `server` surface, so it credits what is
 *   actually running rather than everything OmniSign builds anywhere.
 * @property license SPDX identifier of OmniSign's own licence, distinct from those of the
 *   components it bundles.
 * @property source Where OmniSign's corresponding source can be obtained.
 * @property notices Where the full notices live, including the verbatim attribution text the
 *   shipped artifacts carry and the full text of every licence named here.
 * @property poweredBy The fixed OmniSign product name ([PRODUCT_NAME]), matching the health and
 *   capability responses.
 */
@Serializable
data class CreditsResponse(
    val components: List<ThirdPartyComponent>,
    val license: String = OMNISIGN_LICENSE_ID,
    val source: String = OMNISIGN_SOURCE_URL,
    val notices: String = THIRD_PARTY_NOTICES_URL,
    val poweredBy: String = PRODUCT_NAME,
)
