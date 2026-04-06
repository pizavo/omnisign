package cz.pizavo.omnisign.data.repository

import javax.naming.ldap.LdapName
import javax.security.auth.x500.X500Principal

/**
 * Extract the Common Name (CN) from an [X500Principal], falling back to the full
 * distinguished name when no CN attribute is present.
 *
 * The CN is typically the most recognizable part of a certificate subject
 * (e.g. "PostSignum Qualified CA 4") and is suitable for display alongside the
 * technical DSS identifier.
 *
 * @param principal The certificate subject principal.
 * @return The CN value, or the full RFC 2253 DN when the CN is absent.
 */
internal fun extractSubjectCN(principal: X500Principal): String {
	return try {
		val ldapName = LdapName(principal.getName(X500Principal.RFC2253))
		ldapName.rdns
			.firstOrNull { it.type.equals("CN", ignoreCase = true) }
			?.value
			?.toString()
			?: principal.name
	} catch (_: Exception) {
		principal.name
	}
}

