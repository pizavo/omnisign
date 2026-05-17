package cz.pizavo.omnisign.data.service

import com.sun.jna.Library
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * JNA binding for the PKCS#11 functions needed to probe token identities.
 *
 * Uses cdecl convention as mandated by the PKCS#11 v2.20 spec.  Only the
 * read-only subset of the API is exposed — enough for `C_Initialize`,
 * `C_GetSlotList`, and `C_GetTokenInfo` — so that probing never calls
 * `C_Login` and therefore never risks incrementing a wrong-PIN counter.
 *
 * Used exclusively from inside [Pkcs11ProbeWorker] (subprocess) so that any native crash
 * stays out of the host JVM.  No in-process consumer of this binding exists.
 */
internal interface Pkcs11ProbeLib : Library {

	/**
	 * Initialize the PKCS#11 library.
	 */
	fun C_Initialize(pInitArgs: Pointer?): NativeLong

	/**
	 * List slots that optionally have a token present.
	 */
	fun C_GetSlotList(tokenPresent: Byte, pSlotList: Pointer?, pulCount: Pointer?): NativeLong

	/**
	 * Gather information about a particular token in the specified slot.
	 */
	fun C_GetTokenInfo(slotID: NativeLong, pInfo: Pointer?): NativeLong

	/**
	 * Open a session between an application and a token in a slot.  The "Route A"
	 * no-login probe opens a **read-only** (`CKF_SERIAL_SESSION`-only) session and
	 * never follows it with `C_Login`, so only public objects are visible.
	 */
	fun C_OpenSession(
		slotID: NativeLong,
		flags: NativeLong,
		pApplication: Pointer?,
		notify: Pointer?,
		phSession: Pointer?,
	): NativeLong

	/**
	 * Close a session between an application and a token.
	 */
	fun C_CloseSession(hSession: NativeLong): NativeLong

	/**
	 * Initialize a search for token and session objects that match a template.
	 */
	fun C_FindObjectsInit(hSession: NativeLong, pTemplate: Pointer?, ulCount: NativeLong): NativeLong

	/**
	 * Continue a search for token and session objects, obtaining additional object handles.
	 */
	fun C_FindObjects(
		hSession: NativeLong,
		phObject: Pointer?,
		ulMaxObjectCount: NativeLong,
		pulObjectCount: Pointer?,
	): NativeLong

	/**
	 * Finish a search for token and session objects.
	 */
	fun C_FindObjectsFinal(hSession: NativeLong): NativeLong

	/**
	 * Obtain the value of one or more attributes of an object.  Used with the
	 * standard two-pass idiom: first call with `pValue = NULL` to learn the
	 * required buffer length, then again with an allocated buffer.
	 */
	fun C_GetAttributeValue(
		hSession: NativeLong,
		hObject: NativeLong,
		pTemplate: Pointer?,
		ulCount: NativeLong,
	): NativeLong
}

/**
 * PKCS#11 return value: operation completed successfully.
 */
internal const val CKR_OK = 0L

/**
 * PKCS#11 return value: the library was already initialized in this process.
 *
 * Treated as success — the library is ready for slot/token queries.
 */
internal const val CKR_CRYPTOKI_ALREADY_INITIALIZED = 0x191L

/**
 * Byte offset of the `label` field within a `CK_TOKEN_INFO` structure.
 */
internal const val CK_TOKEN_INFO_LABEL_OFFSET = 0

/**
 * Length in bytes of the `label` field within a `CK_TOKEN_INFO` structure.
 */
internal const val CK_TOKEN_INFO_LABEL_LEN = 32

/**
 * Length in bytes of the `manufacturerID` field within a `CK_TOKEN_INFO` structure.
 */
internal const val CK_TOKEN_INFO_MANUFACTURER_LEN = 32

/**
 * Length in bytes of the `model` field within a `CK_TOKEN_INFO` structure.
 */
internal const val CK_TOKEN_INFO_MODEL_LEN = 16

/**
 * Byte offset of the `serialNumber` field within a `CK_TOKEN_INFO` structure.
 */
internal const val CK_TOKEN_INFO_SERIAL_OFFSET =
	CK_TOKEN_INFO_LABEL_LEN + CK_TOKEN_INFO_MANUFACTURER_LEN + CK_TOKEN_INFO_MODEL_LEN

/**
 * Length in bytes of the `serialNumber` field within a `CK_TOKEN_INFO` structure.
 */
internal const val CK_TOKEN_INFO_SERIAL_LEN = 16

/**
 * Allocation size for reading a full `CK_TOKEN_INFO` structure via JNA.
 */
internal const val CK_TOKEN_INFO_SIZE = 256

/**
 * PKCS#11 return value: the supplied buffer was too small; `ulValueLen` now holds
 * the required length.  Treated like success by the two-pass `C_GetAttributeValue`
 * sizing call.
 */
internal const val CKR_BUFFER_TOO_SMALL = 0x150L

/**
 * `CK_OBJECT_CLASS` value for an X.509 certificate object (`CKO_CERTIFICATE`).
 */
internal const val CKO_CERTIFICATE = 0x00000001L

/**
 * `CK_ATTRIBUTE_TYPE` for the object class (`CKA_CLASS`).
 */
internal const val CKA_CLASS = 0x00000000L

/**
 * `CK_ATTRIBUTE_TYPE` for the object label (`CKA_LABEL`, UTF-8 text).
 */
internal const val CKA_LABEL = 0x00000003L

/**
 * `CK_ATTRIBUTE_TYPE` for the DER-encoded certificate value (`CKA_VALUE`).
 */
internal const val CKA_VALUE = 0x00000011L

/**
 * `CK_ATTRIBUTE_TYPE` for the key/cert pairing identifier (`CKA_ID`).  This is the
 * value a private key and its certificate share, so it is the robust join key for a
 * future no-login-list → logged-in-sign handoff.
 */
internal const val CKA_ID = 0x00000102L

/**
 * `CK_SESSION_INFO` flag marking a serial session; a read-only session sets this
 * flag only (no `CKF_RW_SESSION`, no `C_Login`).
 */
internal const val CKF_SERIAL_SESSION = 0x00000004L

/**
 * Decode a fixed-length PKCS#11 text field and strip padding.
 *
 * The PKCS#11 v2.20 specification mandates space-padding (`0x20`) for fixed-length
 * character fields such as `CK_TOKEN_INFO.label` and `CK_TOKEN_INFO.serialNumber`.
 * However, some middleware implementations (notably SafeNet Authentication Client)
 * use null-byte (`0x00`) padding instead.  This extension handles both conventions
 * by replacing null bytes with spaces before trimming.
 *
 * @receiver Raw byte array read from a `CK_TOKEN_INFO` field via JNA.
 * @return The decoded, trimmed UTF-8 string with all padding removed.
 */
internal fun ByteArray.trimPkcs11Field(): String =
	String(this, Charsets.UTF_8)
		.replace('\u0000', ' ')
		.trim()

