package cz.pizavo.omnisign.auth

import java.net.IDN

/**
 * Determines whether an authenticated user is admissible under the operator's
 * `allowedEmailDomains` policy.
 *
 * Two acceptance modes are supported:
 * - The wildcard entry `"*"`, when present anywhere in [allowedDomains], grants access
 *   to **every authenticated user** regardless of whether the IdP returned an email at
 *   all. This is the explicit "allow any authenticated user" choice, parallel to CORS
 *   `allowedOrigins: ["*"]`. A `null` [email] is admitted under the wildcard because
 *   `["*"]` is operator-chosen explicit allow-all — a user without an email cannot
 *   override that.
 * - Otherwise the email's domain (everything after the last `@`) is compared against each
 *   entry; access is granted on any match. A `null` [email] under this branch is rejected
 *   (fail closed) — the operator wrote concrete domains as an access-control decision, and
 *   a user the IdP cannot vouch for an email for cannot be checked against that policy.
 *   An admitted address must also be one the IdP vouches for: see
 *   [isEmailVerificationAccepted].
 *
 * Both sides are compared in their ASCII form (IDNA `ToASCII` with the STD3 rules, then
 * lower-cased), so an internationalised domain matches its `xn--` spelling. Ignoring case
 * character by character instead would fold lookalikes such as the dotless `ı` of
 * `fabrıkam.com`, a different and registrable domain, onto `fabrikam.com`. A domain or entry
 * that is no valid host name matches nothing.
 *
 * Empty and missing [allowedDomains] are not handled here — they are rejected at
 * server startup so the filter can rely on receiving a deliberate, non-empty
 * operator choice. See `OidcProviderConfig.allowedEmailDomains` for the
 * configuration contract.
 *
 * @param email The user's email address resolved from IdP claims, or `null` when the
 *   IdP did not supply one (e.g. a GitHub user with private email, or an IdP that does
 *   not release the email claim).
 * @param allowedDomains Non-empty list of permitted domain entries (e.g. `["contoso.com"]`)
 *   or the singleton `["*"]` to allow every authenticated user.
 * @return `true` when [allowedDomains] contains `"*"`, or when [email] is non-null and
 *   its domain matches at least one entry.
 */
internal fun isEmailDomainAllowed(email: String?, allowedDomains: List<String>): Boolean {
    if ("*" in allowedDomains) return true
    if (email == null) return false
    val domain = asciiDomain(email.substringAfterLast("@", missingDelimiterValue = "")) ?: return false
    return allowedDomains.any { asciiDomain(it) == domain }
}

/**
 * Convert [domain] to the lower-case ASCII form DNS uses for it.
 *
 * @return The converted domain, or `null` when [domain] is empty or no valid host name.
 */
private fun asciiDomain(domain: String): String? {
    if (domain.isEmpty()) return null
    return try {
        IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).lowercase()
    } catch (_: IllegalArgumentException) {
        null
    }
}
