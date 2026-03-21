package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.alert.StatusAlert
import eu.europa.esig.dss.alert.status.ObjectStatus
import eu.europa.esig.dss.alert.status.Status
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [StatusAlert] implementation that collects alert messages into a thread-safe list
 * instead of only logging them.
 *
 * Used to surface DSS certificate-verifier warnings (missing revocation data, uncovered
 * proof-of-existence, invalid timestamps, etc.) as structured, user-visible entries in
 * operation results rather than relying on log output that may be suppressed by the
 * logback configuration.
 *
 * When DSS fires an [ObjectStatus] alert (the common case for revocation and POE
 * warnings), the per-object details are appended to the main message, replicating
 * the formatting that DSS's own [eu.europa.esig.dss.alert.handler.LogHandler] uses.
 *
 * Typical lifecycle:
 * 1. Create an instance and wire it into the [eu.europa.esig.dss.spi.validation.CommonCertificateVerifier].
 * 2. Run the DSS operation (sign, extend, validate).
 * 3. Call [drain] to retrieve and clear the collected messages.
 */
class CollectingStatusAlert : StatusAlert {
	
	private val messages = CopyOnWriteArrayList<String>()
	
	/**
	 * Called by DSS when the certificate verifier encounters a non-fatal condition.
	 *
	 * For [ObjectStatus] alerts the per-object detail map is formatted and appended
	 * to the base message so that callers receive a single, self-contained string
	 * equivalent to the output of DSS's built-in [eu.europa.esig.dss.alert.handler.LogHandler].
	 */
	override fun alert(status: Status) {
		val formatted = formatStatus(status)
		if (!formatted.isNullOrBlank()) {
			messages += formatted
		}
	}
	
	/**
	 * Return all collected alert messages and clear the internal buffer.
	 *
	 * Safe to call from any thread. Subsequent calls return only messages collected
	 * after the previous drain.
	 */
	fun drain(): List<String> {
		val snapshot = messages.toList()
		messages.clear()
		return snapshot
	}
	
	companion object {
		/**
		 * Format a DSS [Status] into a single human-readable string.
		 *
		 * For [ObjectStatus] instances that carry per-object entries (certificate IDs,
		 * timestamps, etc.), the individual messages are appended in bracket notation
		 * after the base message, matching the format produced by DSS's
		 * [eu.europa.esig.dss.alert.handler.LogHandler].
		 *
		 * Falls back to [Status.getMessage] for plain [eu.europa.esig.dss.alert.status.MessageStatus].
		 */
		internal fun formatStatus(status: Status): String? {
			if (status !is ObjectStatus) return status.message
			
			val base = status.message
			val objectIds = status.relatedObjectIds
			if (objectIds.isNullOrEmpty()) return base
			
			val details = objectIds.mapNotNull { id ->
				val msg = status.getMessageForObjectWithId(id)
					?.takeIf { it.isNotBlank() }
					?: return@mapNotNull null
				"$id: $msg"
			}
			
			if (details.isEmpty()) return base
			
			val suffix = details.joinToString("; ", prefix = " [", postfix = "]")
			return if (base.isNullOrBlank()) suffix.trimStart() else "$base$suffix"
		}
	}
}


