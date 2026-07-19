package cz.pizavo.omnisign.ui.model

/**
 * UI-facing view of which operations the connected server permits.
 *
 * The operation flags **default to `false` (deny)** so the web target fails closed: until a
 * successful `GET /api/v1/capabilities` response confirms otherwise, no operation affordance is
 * shown, and a capabilities fetch that fails leaves every operation denied rather than exposing a
 * button that cannot work. [cz.pizavo.omnisign.ui.viewmodel.CapabilitiesViewModel] narrows these
 * flags to the server's reported `allowedOperations` on success.
 *
 * The desktop target has no server to gate it, so the view model sets every operation flag to `true`
 * explicitly there — the deny-by-default is a web concern only.
 *
 * @property canValidate Whether signature validation is offered (server `VALIDATE`).
 * @property canSign Whether the Sign affordance is offered (server `SIGN`).
 * @property canTimestamp Whether the Timestamp / extend affordance is offered (server `TIMESTAMP`).
 * @property authEnabled Whether the server requires an authenticated session (server
 *   `authEnabled`). `false` on desktop and on a server with no SSO configured, in which case the
 *   app runs open. When `true`, the web target only reaches a rendered app once a session exists,
 *   and the UI can offer a sign-out affordance. Carries no meaning on desktop.
 * @property organizationName Deploy-time branding label of the operator running the server, from the
 *   `GET /api/v1/capabilities` `organizationName`; `null` when the operator set none or there is no
 *   server (desktop). Composed with the frontend deployer's label into the displayed title.
 */
data class ServerCapabilities(
    val canValidate: Boolean = false,
    val canSign: Boolean = false,
    val canTimestamp: Boolean = false,
    val authEnabled: Boolean = false,
    val organizationName: String? = null,
)
