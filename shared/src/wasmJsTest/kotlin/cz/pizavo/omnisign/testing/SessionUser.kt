package cz.pizavo.omnisign.testing

import cz.pizavo.omnisign.api.model.responses.SessionResponse

/**
 * The user a `TokenResponse` names, as the server describes a session's owner.
 *
 * The defaults are the one user most specs sign in as; vary [userId] or [providerName] to stand
 * for somebody else.
 *
 * @param userId The identity provider's subject for the user.
 * @param providerName The provider that authenticated the user.
 */
fun sessionUser(userId: String = "user-1", providerName: String = "oidc"): SessionResponse = SessionResponse(
	userId = userId,
	email = "signer@example.com",
	displayName = "Signer",
	providerName = providerName,
)
