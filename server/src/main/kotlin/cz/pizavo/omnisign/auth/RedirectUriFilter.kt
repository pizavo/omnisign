package cz.pizavo.omnisign.auth

/**
 * Determines whether a completed login may hand the browser back to [returnTo], under the
 * operator's `auth.allowedRedirectUris` policy.
 *
 * One acceptance mode, deliberately: an exact string match against a listed entry. No wildcard,
 * no prefix, no host-only comparison, and no `["*"]` escape hatch — which makes this the one
 * allowlist in the auth configuration that offers no allow-all, in pointed contrast to
 * [isEmailDomainAllowed] and CORS `allowedOrigins`. The difference is what the two lists gate.
 * Those decide who may hold a session the server has already decided to issue; this one decides
 * *where the credential is delivered*, and a redirect allowlist with any give in it is the
 * classic open-redirect-into-token-theft primitive:
 *
 * - Prefix matching on `https://app.example.com` also admits `https://app.example.com.evil.test`
 *   — a different site whose name merely starts the same way.
 * - Host matching admits every path on the host, including one an attacker can plant content at
 *   (an uploads directory, a user profile page, an unclaimed sub-path behind a proxy).
 * - A wildcard admits the attacker's own server.
 *
 * In each case the login endpoint keeps working exactly as designed and mails a hand-off code to
 * a page of the attacker's choosing. Since an exact match costs the operator nothing but writing
 * the URL out in full, there is no reason to accept anything looser.
 *
 * Empty [allowedRedirectUris] rejects everything, which is the correct reading of an operator who
 * has not named any front-end: no browser hand-off is configured, so no hand-off is performed.
 * Entry syntax is checked at server startup rather than here — see
 * `AuthConfig.allowedRedirectUris` for the configuration contract.
 *
 * @param returnTo The URL the client asked to be returned to.
 * @param allowedRedirectUris The operator's list of permitted return URLs, possibly empty.
 * @return `true` only when [returnTo] appears verbatim in [allowedRedirectUris].
 */
internal fun isRedirectUriAllowed(returnTo: String, allowedRedirectUris: List<String>): Boolean =
    returnTo in allowedRedirectUris
