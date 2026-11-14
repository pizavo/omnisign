package cz.pizavo.omnisign.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long a login may take from `/auth/redirect/{provider}` to the identity provider's callback.
 *
 * Every record of that round trip lives this long: the OAuth `state`, which the server's nonce
 * manager stops accepting after it, the PKCE verifier [PkceService] stores, and the
 * [LoginRequest] a browser hand-off parks. They share one lifetime because the callback needs all
 * of them, so a record that expired sooner would fail the login while the others were still
 * valid. Five minutes leave room for a password, an MFA prompt, a consent page or a federation's
 * organisation picker, and keep abandoned flows from lingering in the store.
 */
val LOGIN_FLOW_TTL: Duration = 5.minutes
