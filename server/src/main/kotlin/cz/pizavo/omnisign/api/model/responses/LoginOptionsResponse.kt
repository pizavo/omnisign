package cz.pizavo.omnisign.api.model.responses

import kotlinx.serialization.Serializable

/**
 * Response body returned by `GET /auth/login` listing all configured SSO providers.
 *
 * @property providers Available authentication providers the client may choose from.
 */
@Serializable
data class LoginOptionsResponse(
    val providers: List<ProviderInfo>,
) {
    /**
     * Metadata for a single SSO provider shown in the login chooser.
     *
     * @property name Machine-readable provider identifier used in callback URLs.
     * @property displayName Human-readable label for the login button.
     * @property type Protocol type: `oidc` or `header-injection`.
     * @property loginUrl URL to redirect the browser to in order to start the login flow.
     */
    @Serializable
    data class ProviderInfo(
        val name: String,
        val displayName: String,
        val type: String,
        val loginUrl: String,
    )
}

