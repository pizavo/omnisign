package cz.pizavo.omnisign.ui.branding

import androidx.compose.runtime.staticCompositionLocalOf
import cz.pizavo.omnisign.branding.PRODUCT_NAME

/** The separator between segments of the branded title, e.g. `"University of Ostrava · OmniSign"`. */
private const val TITLE_SEPARATOR: String = " · "

/**
 * The deploy-time organization label of the party that deployed *this frontend* — set on the web
 * target from the bundle's `web-config.json` `organizationName`, or `null` when unset. Provided once at
 * the app root; always `null` off the web target (desktop and CLI are unbranded). Combined with
 * [LocalServerOrganizationName] and the fixed [PRODUCT_NAME] to compose the title — see [brandedTitle].
 */
val LocalOrganizationName = staticCompositionLocalOf<String?> { null }

/**
 * The deploy-time organization label of the *operator running the server* — read from the server's
 * `GET /api/v1/capabilities` `organizationName`, or `null` when the operator set none (or there is no
 * server, as on desktop). Provided once the capabilities load. In a white-label chain the frontend
 * deployer ([LocalOrganizationName]) and the server operator can differ, so both are shown — see
 * [brandedTitle].
 */
val LocalServerOrganizationName = staticCompositionLocalOf<String?> { null }

/**
 * The ordered, de-duplicated chain of provider labels, most-local first: the frontend deployer
 * ([webOrganizationName]) then the server operator ([serverOrganizationName]), with blanks dropped and
 * duplicates collapsed — the common case is a single provider deploying both, so the two coincide and
 * appear once. Empty when neither is set. Never includes the [PRODUCT_NAME].
 */
private fun organizationChain(webOrganizationName: String?, serverOrganizationName: String?): List<String> =
    listOfNotNull(
        webOrganizationName?.takeIf { it.isNotBlank() },
        serverOrganizationName?.takeIf { it.isNotBlank() },
    ).distinct()

/**
 * The provider-label line shown above the open-a-file prompt in the no-document branding block: the
 * de-duplicated [organizationChain] joined by ` · ` (e.g. `"University of Ostrava · Microsoft"`), or
 * `null` when no provider branding is set. The [PRODUCT_NAME] is deliberately *not* included here — the
 * block renders the `powered by OmniSign` attribution separately beneath this line.
 */
fun organizationChainLabel(webOrganizationName: String?, serverOrganizationName: String?): String? =
    organizationChain(webOrganizationName, serverOrganizationName)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(TITLE_SEPARATOR)

/**
 * Compose the app's display title from the provider chain and the fixed product name: the
 * de-duplicated [organizationChain] followed by [PRODUCT_NAME], joined by ` · ` — e.g.
 * `"University of Ostrava · Microsoft · OmniSign"`, `"University of Ostrava · OmniSign"`, or just
 * `"OmniSign"` when no provider branding is set. The OmniSign segment is always present and last, so
 * attribution is preserved regardless of the provider labels. Shared by the browser tab title and the
 * toolbar logo tooltip so both read identically.
 */
fun brandedTitle(webOrganizationName: String?, serverOrganizationName: String?): String =
    (organizationChain(webOrganizationName, serverOrganizationName) + PRODUCT_NAME).joinToString(TITLE_SEPARATOR)
