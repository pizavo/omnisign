package cz.pizavo.omnisign.ui.model

/**
 * UI-facing view of which operations the connected server permits.
 *
 * On the desktop target there is no server, so every flag defaults to `true` — the local
 * app can perform every operation. On the web target a
 * [cz.pizavo.omnisign.ui.viewmodel.CapabilitiesViewModel] queries the server's
 * `GET /api/v1/capabilities` and narrows these flags to the reported `allowedOperations`,
 * so the UI hides affordances for operations the server does not expose.
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
    val canValidate: Boolean = true,
    val canSign: Boolean = true,
    val canTimestamp: Boolean = true,
    val authEnabled: Boolean = false,
    val organizationName: String? = null,
)
