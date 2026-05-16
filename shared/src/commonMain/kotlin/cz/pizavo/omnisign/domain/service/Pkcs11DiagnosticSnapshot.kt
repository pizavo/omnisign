package cz.pizavo.omnisign.domain.service

/**
 * Lightweight, read-only snapshot of what the local PKCS#11 discovery layer currently sees.
 *
 * Produced on demand by [TokenService.getDiagnosticSnapshot] when the user clicks
 * "Show diagnostic info" in the sign dialog's empty-state banner.  Intentionally avoids
 * running fresh subprocess probes — this is the equivalent of a cached "what's plugged in
 * right now and what would discovery consider" report, not a deep validation pass
 * (which the `omnisign diagnose pkcs11` CLI subcommand provides).
 *
 * Multiplatform-safe: the JVM implementation populates real data; web / non-discovery
 * implementations return an empty snapshot so the UI degrades gracefully.
 *
 * @property pcscReaders PC/SC readers currently visible to the operating system, with their
 *   card-presence flag and ATR hex when a card is inserted.  Empty when the PC/SC stack is
 *   unavailable.
 * @property candidateLibraries Deduplicated `(displayName, absolutePath)` pairs that the
 *   discoverer would attempt to probe right now — the union of OS-native sources, the
 *   drop-directory contents, and user-supplied entries.
 * @property dropDirectoryPath Absolute path of the platform-appropriate PKCS#11 drop
 *   directory (`<appData>/omnisign/pkcs11/`); shown so the user knows where to copy a
 *   library file for automatic pickup.  `null` on implementations that have no drop
 *   directory concept.
 */
data class Pkcs11DiagnosticSnapshot(
	val pcscReaders: List<PcscReaderInfo>,
	val candidateLibraries: List<CandidateLibrary>,
	val dropDirectoryPath: String?,
) {

	/**
	 * One PC/SC reader visible to the operating system.
	 *
	 * @property name Reader name as reported by the platform PC/SC stack (e.g.
	 *   "SafeNet Token JC 0" on Windows or "Yubico YubiKey OTP+FIDO+CCID" on Linux).
	 * @property cardPresent `true` when a card is currently inserted into this reader.
	 * @property atrHex Card ATR as an uppercase hex string when [cardPresent] is `true`
	 *   and the ATR could be read; `null` when no card is inserted or ATR retrieval failed.
	 */
	data class PcscReaderInfo(
		val name: String,
		val cardPresent: Boolean,
		val atrHex: String?,
	)

	/**
	 * One candidate PKCS#11 library that discovery would probe.
	 *
	 * @property displayName Vendor-friendly label derived from the path
	 *   (e.g. "SafeNet eToken", "p11-kit Proxy", or the bare filename for unknown vendors).
	 * @property path Absolute path to the library on the local filesystem.
	 */
	data class CandidateLibrary(
		val displayName: String,
		val path: String,
	)

	companion object {
		/**
		 * Empty snapshot returned by platform implementations that don't perform local
		 * PKCS#11 discovery (web target, future remote-only services).  Lets the UI render
		 * a "no diagnostic info available on this platform" state without special-casing
		 * `null` returns at every call site.
		 */
		val EMPTY: Pkcs11DiagnosticSnapshot = Pkcs11DiagnosticSnapshot(
			pcscReaders = emptyList(),
			candidateLibraries = emptyList(),
			dropDirectoryPath = null,
		)
	}
}
