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
 * that is no valid host name matches nothing, and
 * [validateAuthConfig][cz.pizavo.omnisign.config.validateAuthConfig] refuses such an entry at
 * startup (see [isMatchableEmailDomainEntry]).
 *
 * Java's conversion follows IDNA2003, which rewrites a few characters that today's IDNA2008
 * registries keep: `ß` becomes `ss`, the final sigma `ς` becomes `σ`, and the zero-width
 * joiners vanish. `claß.com`, registered as its own domain `xn--cla-pia.com`, would therefore
 * pass for `class.com`. So a domain written with non-ASCII characters counts only if
 * converting it back gives the same domain, ignoring case; any other matches nothing. A
 * domain with such characters can still be listed, and matched, in its `xn--` spelling.
 *
 * Empty and missing [allowedDomains] are not handled here — they are rejected at
 * server startup so the filter can rely on receiving a deliberate, non-empty
 * operator choice. So are `"*"` beside other entries and an entry no address can
 * match. See `OidcProviderConfig.allowedEmailDomains` for the configuration contract.
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
 * Whether [entry], one entry of a provider's `allowedEmailDomains`, is one [isEmailDomainAllowed]
 * can match an address to: the wildcard `"*"`, or a domain name that converts to the ASCII form
 * the comparison uses and does not end with a dot. An entry with a trailing dot could match only
 * an address whose domain ends with one too, which RFC 5321 does not allow.
 *
 * [validateAuthConfig][cz.pizavo.omnisign.config.validateAuthConfig] refuses any other entry at
 * startup, since it would admit nobody: `"@contoso.com"`, `"*.contoso.com"`,
 * `"https://contoso.com"` and `" contoso.com"` among them, and a domain the conversion would
 * rewrite into another, such as `"claß.com"`, which matches only in its `xn--` spelling.
 *
 * @param entry The configured entry, as written.
 * @return `true` when some address can match [entry].
 */
internal fun isMatchableEmailDomainEntry(entry: String): Boolean =
    entry == "*" || (!entry.endsWith(".") && asciiDomain(entry) != null)

/**
 * Convert [domain] to the lower-case ASCII form DNS uses for it.
 *
 * An ASCII domain, `xn--` spellings included, converts to itself. A domain with other
 * characters converts only when converting the result back gives [domain] again, ignoring
 * case, so a domain that the conversion would rewrite into another one cannot pass for it.
 *
 * @return The converted domain, or `null` when [domain] is empty, no valid host name, or
 *   rewritten by the conversion.
 */
private fun asciiDomain(domain: String): String? {
    if (domain.isEmpty()) return null
    val ascii = try {
        IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val faithful = domain.all { it <= '\u007F' } ||
        IDN.toUnicode(ascii, IDN.USE_STD3_ASCII_RULES).lowercase() == domain.lowercase()
    return ascii.lowercase().takeIf { faithful }
}
