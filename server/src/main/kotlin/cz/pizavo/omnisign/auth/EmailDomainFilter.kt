package cz.pizavo.omnisign.auth

/**
 * Determines whether an email address belongs to one of the permitted domains.
 *
 * When [allowedDomains] is `null` or empty, every email is accepted (no restriction
 * configured). Domain comparison is case-insensitive.
 *
 * @param email The user's email address resolved from IdP claims.
 * @param allowedDomains Ordered list of permitted domain suffixes (e.g. `["contoso.com"]`),
 *   or `null` to disable the check entirely.
 * @return `true` when the email's domain matches at least one entry in [allowedDomains],
 *   or when [allowedDomains] is `null` or empty.
 */
internal fun isEmailDomainAllowed(email: String, allowedDomains: List<String>?): Boolean {
    if (allowedDomains.isNullOrEmpty()) return true
    val domain = email.substringAfterLast("@", missingDelimiterValue = "")
    return domain.isNotEmpty() && allowedDomains.any { it.equals(domain, ignoreCase = true) }
}

