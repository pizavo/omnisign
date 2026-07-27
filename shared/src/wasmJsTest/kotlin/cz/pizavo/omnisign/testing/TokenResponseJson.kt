package cz.pizavo.omnisign.testing

import cz.pizavo.omnisign.api.model.responses.SessionResponse
import cz.pizavo.omnisign.api.model.responses.TokenResponse
import kotlinx.serialization.json.Json

/**
 * A serialized `TokenResponse` body as `/auth/exchange` and `/auth/refresh` return one.
 *
 * Built from the real DTO rather than hand-written JSON so a field the server adds cannot silently
 * drift away from what these specs feed the client.
 *
 * @param accessToken The short-lived JWT the client attaches as `Authorization: Bearer …`.
 * @param refreshToken The rotated refresh token the client spends on the next refresh.
 */
fun tokenResponseJson(accessToken: String, refreshToken: String): String = Json.encodeToString(
	TokenResponse(
		token = accessToken,
		refreshToken = refreshToken,
		expiresIn = 300,
		user = SessionResponse(
			userId = "user-1",
			email = "signer@example.com",
			displayName = "Signer",
			providerName = "oidc",
		),
	),
)
