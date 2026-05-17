package cz.pizavo.omnisign.data.service

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import javax.smartcardio.CardTerminal
import javax.smartcardio.TerminalFactory

/**
 * Windows-only resolver: maps the cards in connected PC/SC readers to their PKCS#11
 * middleware library paths via the Calais registry.
 *
 * For every reader with a card inserted it reads the card's ATR and looks the matching
 * `Pkcs11Lib` path up in
 * `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`.  Only library paths that
 * actually exist on disk are returned; naming and merging into the candidate set is the
 * caller's ([Pkcs11CandidateCollector]) concern.
 *
 * This is the single owner of the PC/SC enumeration + Calais lookup responsibility,
 * including transparent recovery from the JDK `sun.security.smartcardio` stale-context
 * defect via [PcscContextRecovery].  It is split out of [Pkcs11CandidateCollector] so the
 * Windows smart-card stack stays isolated from the OS-source merge logic.
 *
 * @property pcscRecovery Recovers the JDK's process-wide PC/SC context after the
 *   `sun.security.smartcardio` stale-context defect so [resolvePkcs11Paths] can
 *   re-enumerate readers within the same JVM session instead of returning empty until
 *   restart.  See [PcscContextRecovery].
 */
class Pkcs11PcscCalaisResolver(
	private val pcscRecovery: PcscContextRecovery = PcscContextRecovery(),
) {

	/**
	 * List PC/SC smart card readers via [javax.smartcardio.TerminalFactory] and resolve the
	 * PKCS#11 middleware path for each inserted card from
	 * `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`.
	 *
	 * Returns an empty list when no smart card service is running, no readers are connected,
	 * or the platform PC/SC stack is unreachable.  Genuine per-reader failures (mute card,
	 * JNA registry load issues, unexpected connect errors) are logged at WARN with their
	 * stack trace and swallowed; the known stale-context churn during a probe is logged
	 * concisely at INFO and recovered elsewhere (the watcher and the custom-library path),
	 * so discovery degrades gracefully to the user-supplied escape hatches in
	 * [Pkcs11CandidateCollector.collectCandidates].
	 *
	 * The implementation uses [javax.smartcardio] (the same stack [PcscMonitorService] uses
	 * for hot-insert events) rather than direct `winscard.dll` JNA calls.  Both are equivalent
	 * on Windows, but the smartcardio path is more robust against JNA marshaling issues that
	 * have caused silent enumeration failures in the past.
	 *
	 * @return Absolute paths to PKCS#11 libraries resolved from inserted cards that exist on
	 *   disk; never `null`, possibly empty.
	 */
	fun resolvePkcs11Paths(): List<String> {
		val terminals = listPcscTerminals()

		if (terminals.isEmpty()) {
			logger.info { "pcscCalais: no PC/SC readers detected" }
			return emptyList()
		}

		logger.info { "pcscCalais: ${terminals.size} reader(s) found: ${terminals.map { it.name }}" }
		val results = mutableListOf<String>()

		for (terminal in terminals) {
			runCatching {
				if (!terminal.isCardPresent) {
					logger.info { "pcscCalais: reader='${terminal.name}' has no card inserted" }
					return@runCatching
				}
				val card = terminal.connect("*")
				try {
					val atrHex = card.atr.bytes.joinToString("") { "%02X".format(it) }
					val resolvedPath = resolveFromAtr(atrHex)
					when {
						resolvedPath == null -> logger.warn { "pcscCalais: reader='${terminal.name}' atr=$atrHex - no matching Pkcs11Lib in Calais registry" }
						!File(resolvedPath).exists() -> logger.warn { "pcscCalais: reader='${terminal.name}' atr=$atrHex resolved to '$resolvedPath' but the file does not exist on disk" }
						else -> {
							logger.info { "pcscCalais: reader='${terminal.name}' atr=$atrHex -> '$resolvedPath'" }
							results += resolvedPath
						}
					}
				} finally {
					runCatching { card.disconnect(false) }
				}
			}.onFailure { e ->
				if (pcscRecovery.isStaleContext(e)) {
					logger.info { "pcscCalais: reader='${terminal.name}' probe skipped — PC/SC context went stale during the probe (known service churn; recovered by the watcher and the custom-library path)" }
				} else {
					logger.warn(e) { "pcscCalais: failed to probe reader '${terminal.name}'" }
				}
			}
		}

		logger.info { "pcscCalais: returning ${results.size} resolved path(s): $results" }
		return results
	}

	/**
	 * Enumerate PC/SC readers, transparently recovering from the JDK
	 * `sun.security.smartcardio` stale-context defect.
	 *
	 * The first failure in a session is typically `SCARD_E_NO_SERVICE` (the Windows
	 * *Smart Card* service is demand-stopped when no reader is attached); the JDK then
	 * caches a dead context and every later `list()` throws `SCARD_E_SERVICE_STOPPED`
	 * for the rest of the session — including the user's manual rescan.  On that
	 * signature [pcscRecovery] clears the stale handle and the enumeration is retried
	 * exactly once.  `SCARD_E_NO_READERS_AVAILABLE` is the benign "no token plugged in"
	 * case — on the first attempt **and** after the reset retry — and degrades to a
	 * clean empty list logged at INFO (no warning / stack trace).  A genuinely
	 * persistent failure that survives the reset is logged at WARN with its stack trace
	 * and degrades to an empty list so discovery falls back to the user-supplied escape
	 * hatches in [Pkcs11CandidateCollector.collectCandidates].
	 */
	private fun listPcscTerminals(): List<CardTerminal> {
		return try {
			TerminalFactory.getDefault().terminals().list()
		} catch (e: Exception) {
			if (pcscRecovery.isStaleContext(e) && pcscRecovery.resetContext()) {
				logger.info { "pcscCalais: stale PC/SC context detected — reset and retrying enumeration" }
				runCatching { TerminalFactory.getDefault().terminals().list() }
					.onFailure { retry ->
						if (pcscRecovery.causeChainContains(retry, PcscContextRecovery.NO_READERS_AVAILABLE)) {
							logger.info { "pcscCalais: no PC/SC readers detected after context reset" }
						} else {
							logger.warn(retry) { "pcscCalais: PC/SC enumeration still failing after context reset" }
						}
					}
					.getOrDefault(emptyList())
			} else if (pcscRecovery.causeChainContains(e, PcscContextRecovery.NO_READERS_AVAILABLE)) {
				emptyList()
			} else {
				logger.warn(e) { "pcscCalais: PC/SC terminal enumeration failed" }
				emptyList()
			}
		}
	}

	/**
	 * Look up the PKCS#11 library for a card by its ATR hex string in
	 * `HKLM\SOFTWARE\Microsoft\Cryptography\Calais\SmartCards`.
	 *
	 * Prefers the subkey whose stored `ATR` exactly matches [atrHex]; falls back to the
	 * first subkey with an existing `Pkcs11Lib` path when no exact match is found.
	 * Returns null when the hive is inaccessible or no entry is found.
	 */
	private fun resolveFromAtr(atrHex: String): String? {
		val root = "SOFTWARE\\Microsoft\\Cryptography\\Calais\\SmartCards"
		runCatching {
			val subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, root)
			var fallback: String? = null

			for (subKey in subKeys) {
				runCatching {
					val values = Advapi32Util.registryGetValues(
						WinReg.HKEY_LOCAL_MACHINE, "$root\\$subKey"
					)
					val pkcs11 = (values["Pkcs11Lib"] ?: values["Crypto Provider"]) as? String
						?: return@runCatching
					if (fallback == null && File(pkcs11).exists()) fallback = pkcs11
					val atr = values["ATR"] as? String ?: return@runCatching
					if (atr.replace(" ", "").equals(atrHex, ignoreCase = true)) return pkcs11
				}
			}
			return fallback
		}
		return null
	}

	private companion object {
		val logger = KotlinLogging.logger {}
	}
}
