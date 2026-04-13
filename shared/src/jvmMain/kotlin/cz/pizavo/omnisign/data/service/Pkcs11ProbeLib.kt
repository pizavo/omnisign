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
 * Shared between [Pkcs11ProbeWorker] (subprocess), [probeTokenIdentities]
 * (in-process one-shot), and [Pkcs11SessionManager] (persistent in-process).
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

