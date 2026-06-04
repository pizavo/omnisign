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
 */
data class ServerCapabilities(
    val canValidate: Boolean = true,
    val canSign: Boolean = true,
    val canTimestamp: Boolean = true,
)
