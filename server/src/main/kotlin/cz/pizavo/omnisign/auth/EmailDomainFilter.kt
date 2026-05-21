package cz.pizavo.omnisign.auth

/**
 * Determines whether an email address belongs to one of the permitted domains.
 *
 * Two acceptance modes are supported:
 * - The wildcard entry `"*"`, when present anywhere in [allowedDomains], grants access to
 *   every email regardless of domain. This is the explicit "allow any authenticated user"
 *   choice, parallel to CORS `allowedOrigins: ["*"]`.
 * - Otherwise, the email's domain (everything after the last `@`) is compared
 *   case-insensitively against each entry; access is granted on any match.
 *
 * Empty and missing lists are not handled here — they are rejected at server startup so the
 * filter can rely on receiving a deliberate, non-empty operator choice. See
 * `OidcProviderConfig.allowedEmailDomains` for the configuration contract.
 *
 * @param email The user's email address resolved from IdP claims.
 * @param allowedDomains Non-empty list of permitted domain entries (e.g. `["contoso.com"]`)
 *   or the singleton `["*"]` to allow every email.
 * @return `true` when [allowedDomains] contains `"*"` or when the email's domain matches at
 *   least one entry.
 */
internal fun isEmailDomainAllowed(email: String, allowedDomains: List<String>): Boolean {
    if ("*" in allowedDomains) return true
    val domain = email.substringAfterLast("@", missingDelimiterValue = "")
    return domain.isNotEmpty() && allowedDomains.any { it.equals(domain, ignoreCase = true) }
}

