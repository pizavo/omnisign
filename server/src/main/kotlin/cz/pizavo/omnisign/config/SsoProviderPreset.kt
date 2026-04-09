package cz.pizavo.omnisign.config

/**
 * Well-known OIDC / OAuth2 provider presets.
 *
 * Each entry supplies the OIDC discovery document URL (or its components for providers
 * that do not follow the standard path) so that the operator only has to specify a
 * [SsoProviderPreset] name plus credentials in `server.yml` rather than every URL.
 *
 * Providers that use a tenant- or realm-scoped URL (e.g. [MICROSOFT], [AMAZON_COGNITO],
 * [KEYCLOAK]) expose a [discoveryUrlTemplate] with `{placeholder}` tokens instead of
 * a static [discoveryUrl].
 *
 * @property discoveryUrl Static OIDC discovery document URL, `null` when a template is used.
 * @property discoveryUrlTemplate Discovery URL template with `{tenant}`, `{region}`,
 *   `{poolId}`, or `{realm}` placeholders; `null` when a static URL is used.
 * @property requiresManualUrls `true` for providers (GitHub) that do not expose a standard
 *   OIDC discovery document and require all endpoints to be configured manually.
 */
enum class SsoProviderPreset(
    val discoveryUrl: String?,
    val discoveryUrlTemplate: String? = null,
    val requiresManualUrls: Boolean = false,
) {
    /**
     * Google Identity Platform.
     *
     * Discovery: `https://accounts.google.com/.well-known/openid-configuration`
     */
    GOOGLE(discoveryUrl = "https://accounts.google.com/.well-known/openid-configuration"),

    /**
     * Microsoft Entra ID (Azure AD).
     *
     * Discovery URL requires a `{tenant}` placeholder — set via [OidcProviderConfig.tenantId].
     * Use `common` for multi-tenant, `organizations` for work/school accounts only, or
     * a specific tenant GUID/domain.
     */
    MICROSOFT(
        discoveryUrl = null,
        discoveryUrlTemplate = "https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration",
    ),

    /**
     * Amazon Cognito User Pools.
     *
     * Discovery URL requires `{region}` and `{poolId}` — set via [OidcProviderConfig.tenantId]
     * in the form `{region}/{poolId}` (e.g. `eu-central-1/eu-central-1_abc123`).
     */
    AMAZON_COGNITO(
        discoveryUrl = null,
        discoveryUrlTemplate = "https://cognito-idp.{region}.amazonaws.com/{poolId}/.well-known/openid-configuration",
    ),

    /**
     * Keycloak (self-hosted).
     *
     * Discovery URL requires `{host}` and `{realm}` — set via [OidcProviderConfig.tenantId]
     * in the form `{host}/{realm}` (e.g. `keycloak.example.com/myrealm`).
     */
    KEYCLOAK(
        discoveryUrl = null,
        discoveryUrlTemplate = "https://{host}/realms/{realm}/.well-known/openid-configuration",
    ),

    /**
     * GitHub OAuth2.
     *
     * GitHub does not expose a standard OIDC discovery document. The authorization,
     * token, and user-info endpoints are hard-coded and set automatically when this
     * preset is selected. Requires [OidcProviderConfig.clientId] and
     * [OidcProviderConfig.clientSecret] only.
     */
    GITHUB(
        discoveryUrl = null,
        requiresManualUrls = true,
    ),

    /**
     * GitLab.com (SaaS) or self-hosted GitLab.
     *
     * For self-hosted instances set [OidcProviderConfig.discoveryUrl] directly.
     */
    GITLAB(discoveryUrl = "https://gitlab.com/.well-known/openid-configuration"),

    /**
     * Auth0.
     *
     * Discovery URL requires `{domain}` — set via [OidcProviderConfig.tenantId]
     * (e.g. `myapp.eu.auth0.com`).
     */
    AUTH0(
        discoveryUrl = null,
        discoveryUrlTemplate = "https://{domain}/.well-known/openid-configuration",
    ),

    /**
     * Sign in with Apple.
     *
     * Discovery: `https://appleid.apple.com/.well-known/openid-configuration`
     */
    APPLE(discoveryUrl = "https://appleid.apple.com/.well-known/openid-configuration"),

    /**
     * Czech Academic Identity Federation (eduID.cz) OIDC proxy service.
     *
     * eduID.cz is operated by CESNET and used by Czech universities, including the
     * University of Ostrava. It's supported OIDC since 2020 and federates into EduGAIN.
     * Register an OIDC client at https://www.eduid.cz/cs/tech/oidc.
     *
     * For institutions whose IdP supports only SAML 2.0 (traditional Shibboleth),
     * use the [HeaderInjectionProviderConfig] provider type behind an Apache/mod_shib
     * reverse proxy instead.
     */
    EDUID_CZ(discoveryUrl = "https://login.cesnet.cz/oidc/.well-known/openid-configuration"),
}

