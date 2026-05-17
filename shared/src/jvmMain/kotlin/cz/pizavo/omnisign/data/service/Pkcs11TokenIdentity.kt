package cz.pizavo.omnisign.data.service

/**
 * Identity of a physical PKCS#11 token as reported by `C_GetTokenInfo`.
 *
 * @property label Token label (up to 32 UTF-8 characters, space-padded by the PKCS#11 spec).
 * @property serialNumber Token serial number (up to 16 characters, space-padded).
 * @property libraryPath Absolute path of the PKCS#11 middleware library that reported this token.
 * @property slotId Slot identifier where the token was found, as returned by
 *   `C_GetSlotList(tokenPresent=CK_TRUE)`.  This is the literal slot ID, not an index into
 *   the slot list — used directly in the SunPKCS11 `slot = ` config directive so that
 *   `C_Login` targets the correct slot on aggregator modules (p11-kit-proxy) where slot 0
 *   is typically the wrong slot.
 */
data class Pkcs11TokenIdentity(
	val label: String,
	val serialNumber: String,
	val libraryPath: String,
	val slotId: Long = 0L,
)
