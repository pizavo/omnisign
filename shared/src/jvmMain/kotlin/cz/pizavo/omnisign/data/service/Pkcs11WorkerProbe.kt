package cz.pizavo.omnisign.data.service

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import java.util.Base64

/**
 * Probe a PKCS#11 [libraryPath] for the identities of all currently inserted tokens.
 *
 * Uses JNA to call `C_Initialize`, `C_GetSlotList(tokenPresent=CK_TRUE)`, and
 * `C_GetTokenInfo` to read the hardware token label and serial number from each
 * occupied slot.  This never calls `C_Login` and therefore never risks incrementing
 * a wrong-PIN counter.
 *
 * Designed to run **only inside a [Pkcs11ProbeWorker] subprocess** so any native crash
 * stays contained.  `C_Initialize` is idempotent (`CKR_CRYPTOKI_ALREADY_INITIALIZED` is
 * treated as success); `C_Finalize` is deliberately not called because the subprocess
 * exits immediately after printing the identities.
 *
 * Returns an empty list when the library cannot be loaded, no slots have tokens, or
 * any PKCS#11 call fails.
 */
internal fun probeTokenIdentities(libraryPath: String): List<Pkcs11TokenIdentity> = runCatching {
	@Suppress("UNCHECKED_CAST")
	val lib = Native.load(libraryPath, Pkcs11ProbeLib::class.java) as Pkcs11ProbeLib
	val initRv = lib.C_Initialize(null).toLong()
	if (initRv != CKR_OK && initRv != CKR_CRYPTOKI_ALREADY_INITIALIZED) return emptyList()

	val countMem = Memory(Native.LONG_SIZE.toLong()).also { it.clear() }
	if (lib.C_GetSlotList(1.toByte(), null, countMem).toLong() != CKR_OK) return emptyList()
	val slotCount = countMem.getNativeLong(0).toLong().toInt()
	if (slotCount <= 0) return emptyList()

	val slotsMem = Memory((slotCount.toLong() * Native.LONG_SIZE))
	slotsMem.clear()
	countMem.setNativeLong(0, NativeLong(slotCount.toLong()))
	if (lib.C_GetSlotList(1.toByte(), slotsMem, countMem).toLong() != CKR_OK) return emptyList()

	val results = mutableListOf<Pkcs11TokenIdentity>()
	for (i in 0 until slotCount) {
		val slotId = slotsMem.getNativeLong((i.toLong() * Native.LONG_SIZE))
		val tokenInfo = Memory(CK_TOKEN_INFO_SIZE.toLong())
		tokenInfo.clear()
		if (lib.C_GetTokenInfo(slotId, tokenInfo).toLong() != CKR_OK) continue

		val label = tokenInfo.getByteArray(CK_TOKEN_INFO_LABEL_OFFSET.toLong(), CK_TOKEN_INFO_LABEL_LEN)
			.trimPkcs11Field()
		val serial = tokenInfo.getByteArray(CK_TOKEN_INFO_SERIAL_OFFSET.toLong(), CK_TOKEN_INFO_SERIAL_LEN)
			.trimPkcs11Field()

		if (serial.isNotBlank()) {
			results += Pkcs11TokenIdentity(
				label = label.ifBlank { serial },
				serialNumber = serial,
				libraryPath = libraryPath,
				slotId = slotId.toLong(),
			)
		}
	}
	results
}.getOrDefault(emptyList())

/**
 * Batch size for `C_FindObjects` calls during no-login certificate enumeration.
 */
private const val PKCS11_FIND_BATCH = 64

/**
 * Hard ceiling on a single attribute's byte length; guards against a misbehaving
 * module returning an absurd `ulValueLen` (or `(CK_ULONG)-1` for sensitive attrs).
 */
private const val PKCS11_MAX_ATTR_BYTES = 4_000_000L

/**
 * Enumerate a PKCS#11 [libraryPath]'s certificate objects **without** `C_Login`.
 *
 * Opens a read-only (`CKF_SERIAL_SESSION`-only) session per token-present slot, finds
 * `CKO_CERTIFICATE` objects, and reads `CKA_VALUE` / `CKA_ID` / `CKA_LABEL`.  No PIN is
 * ever supplied, so this returns exactly the certificates that are public objects on the
 * token — the "Route A premise" check (`pkcs11-tool --list-objects --type cert` without
 * `--login`), performed through OmniSign's own JNA stack.
 *
 * Designed to run **only inside a [Pkcs11ProbeWorker] subprocess** so any native crash is
 * contained.  `C_Initialize` is idempotent; `C_Finalize` is intentionally skipped because
 * the subprocess exits immediately after printing.  Any failure (load, init, slot, session,
 * find) degrades to an empty list so it can never disturb the identity probe that ran first.
 *
 * `CK_ATTRIBUTE` is `{ CK_ULONG type; CK_VOID_PTR pValue; CK_ULONG ulValueLen; }`.  Windows
 * Cryptoki is `pack(1)` while LP64 (Linux/macOS) uses natural alignment, but with
 * `CK_ULONG == Native.LONG_SIZE` and the pointer `== Native.POINTER_SIZE` the field offsets
 * collapse to the same formula on both ABIs, so the offsets below are computed once.
 */
internal fun probeNoLoginCertificates(libraryPath: String): List<Pkcs11NoLoginCertRecord> = runCatching {
	@Suppress("UNCHECKED_CAST")
	val lib = Native.load(libraryPath, Pkcs11ProbeLib::class.java) as Pkcs11ProbeLib
	val initRv = lib.C_Initialize(null).toLong()
	if (initRv != CKR_OK && initRv != CKR_CRYPTOKI_ALREADY_INITIALIZED) return emptyList()

	val ulong = Native.LONG_SIZE
	val ptr = Native.POINTER_SIZE
	val pValueOff = ulong.toLong()
	val lenOff = (ulong + ptr).toLong()
	val attrSize = (ulong + ptr + ulong).toLong()

	val countMem = Memory(ulong.toLong()).also { it.clear() }
	if (lib.C_GetSlotList(1.toByte(), null, countMem).toLong() != CKR_OK) return emptyList()
	val slotCount = countMem.getNativeLong(0).toLong().toInt()
	if (slotCount <= 0) return emptyList()
	val slotsMem = Memory(slotCount.toLong() * ulong).also { it.clear() }
	countMem.setNativeLong(0, NativeLong(slotCount.toLong()))
	if (lib.C_GetSlotList(1.toByte(), slotsMem, countMem).toLong() != CKR_OK) return emptyList()

	val records = mutableListOf<Pkcs11NoLoginCertRecord>()
	for (i in 0 until slotCount) {
		val slotId = slotsMem.getNativeLong(i.toLong() * ulong)
		val sessMem = Memory(ulong.toLong()).also { it.clear() }
		if (lib.C_OpenSession(slotId, NativeLong(CKF_SERIAL_SESSION), null, null, sessMem)
				.toLong() != CKR_OK
		) continue
		val session = sessMem.getNativeLong(0)
		try {
			val classHolder = Memory(ulong.toLong()).also { it.setNativeLong(0, NativeLong(CKO_CERTIFICATE)) }
			val template = Memory(attrSize).also { it.clear() }
			template.setNativeLong(0, NativeLong(CKA_CLASS))
			template.setPointer(pValueOff, classHolder)
			template.setNativeLong(lenOff, NativeLong(ulong.toLong()))
			if (lib.C_FindObjectsInit(session, template, NativeLong(1)).toLong() != CKR_OK) continue

			val handles = Memory(ulong.toLong() * PKCS11_FIND_BATCH).also { it.clear() }
			val foundCount = Memory(ulong.toLong()).also { it.clear() }
			while (true) {
				if (lib.C_FindObjects(session, handles, NativeLong(PKCS11_FIND_BATCH.toLong()), foundCount)
						.toLong() != CKR_OK
				) break
				val n = foundCount.getNativeLong(0).toLong().toInt()
				if (n <= 0) break
				for (h in 0 until n) {
					val obj = handles.getNativeLong(h.toLong() * ulong)
					val der = readPkcs11Attribute(lib, session, obj, CKA_VALUE, pValueOff, lenOff, attrSize)
						?: continue
					val id = readPkcs11Attribute(lib, session, obj, CKA_ID, pValueOff, lenOff, attrSize)
						?: ByteArray(0)
					val label = readPkcs11Attribute(lib, session, obj, CKA_LABEL, pValueOff, lenOff, attrSize)
						?: ByteArray(0)
					records += Pkcs11NoLoginCertRecord(
						slotId = slotId.toLong(),
						ckaIdHex = id.joinToString("") { "%02x".format(it) },
						labelBase64 = Base64.getEncoder().encodeToString(label),
						derBase64 = Base64.getEncoder().encodeToString(der),
					)
				}
				if (n < PKCS11_FIND_BATCH) break
			}
			lib.C_FindObjectsFinal(session)
		} finally {
			lib.C_CloseSession(session)
		}
	}
	records
}.getOrDefault(emptyList())

/**
 * Read one object attribute with the standard two-pass `C_GetAttributeValue` idiom:
 * a NULL-buffer call to learn the length, then a sized call to fetch the bytes.
 *
 * Returns `null` when the attribute is absent, sensitive, or reports an out-of-range
 * length (including the `(CK_ULONG)-1` sentinel, which surfaces as a non-positive Long).
 */
private fun readPkcs11Attribute(
	lib: Pkcs11ProbeLib,
	session: NativeLong,
	obj: NativeLong,
	attrType: Long,
	pValueOff: Long,
	lenOff: Long,
	attrSize: Long,
): ByteArray? {
	val sizing = Memory(attrSize).also { it.clear() }
	sizing.setNativeLong(0, NativeLong(attrType))
	val sizeRv = lib.C_GetAttributeValue(session, obj, sizing, NativeLong(1)).toLong()
	if (sizeRv != CKR_OK && sizeRv != CKR_BUFFER_TOO_SMALL) return null
	val len = sizing.getNativeLong(lenOff).toLong()
	if (len <= 0L || len > PKCS11_MAX_ATTR_BYTES) return null

	val buffer = Memory(len)
	val fetch = Memory(attrSize).also { it.clear() }
	fetch.setNativeLong(0, NativeLong(attrType))
	fetch.setPointer(pValueOff, buffer)
	fetch.setNativeLong(lenOff, NativeLong(len))
	if (lib.C_GetAttributeValue(session, obj, fetch, NativeLong(1)).toLong() != CKR_OK) return null
	return buffer.getByteArray(0, len.toInt())
}
