package cz.pizavo.omnisign.data.util

/**
 * Convert an RFC 2253/4514 distinguished-name string (as produced by DSS, e.g.
 * `CN=Doe\, John,O=Org,C=CZ`) into human-readable text by decoding the value escaping, so an
 * in-value comma reads as `Doe, John` rather than `Doe\, John`.
 *
 * Only the backslash escaping inside attribute values is decoded — both single-character escapes
 * (`\,`, `\+`, `\\`, …) and hexadecimal byte escapes (`\HH`, including multi-byte UTF-8 runs).
 * Relative-distinguished-name separators and attribute types are left exactly as they were, so the
 * structure is unchanged; the result is for display only and is no longer a parseable RFC DN.
 *
 * Returns the input unchanged when it carries no escaping.
 */
internal fun readableDistinguishedName(dn: String): String {
    if ('\\' !in dn) return dn
    val out = StringBuilder(dn.length)
    var i = 0
    while (i < dn.length) {
        val c = dn[i]
        if (c != '\\' || i + 1 >= dn.length) {
            out.append(c)
            i++
            continue
        }
        val firstHex = dn[i + 1].digitToIntOrNull(16)
        val secondHex = if (i + 2 < dn.length) dn[i + 2].digitToIntOrNull(16) else null
        if (firstHex != null && secondHex != null) {
            val bytes = ArrayList<Byte>()
            while (i + 2 < dn.length && dn[i] == '\\') {
                val high = dn[i + 1].digitToIntOrNull(16) ?: break
                val low = dn[i + 2].digitToIntOrNull(16) ?: break
                bytes.add(((high shl 4) or low).toByte())
                i += 3
            }
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        } else {
            out.append(dn[i + 1])
            i += 2
        }
    }
    return out.toString()
}
