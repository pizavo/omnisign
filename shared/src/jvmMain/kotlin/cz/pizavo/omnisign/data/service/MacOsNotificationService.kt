package cz.pizavo.omnisign.data.service

/**
 * [OsNotificationService] for macOS using `osascript`.
 *
 * Urgency is approximated: CRITICAL notifications include a subtitle "⚠ Action required"
 * because macOS does not expose a formal urgency level through `display notification`.
 */
class MacOsNotificationService : OsNotificationService {
	
	override fun notify(title: String, body: String, urgency: NotificationUrgency) {
		try {
			val subtitle = if (urgency == NotificationUrgency.CRITICAL) "⚠ Action required" else "omnisign"
			val safeBody = body.replace("\\", "\\\\").replace("\"", "\\\"")
			val safeTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
			val safeSubtitle = subtitle.replace("\\", "\\\\").replace("\"", "\\\"")
			val script = """display notification "$safeBody" with title "$safeTitle" subtitle "$safeSubtitle""""
			ProcessBuilder("osascript", "-e", script)
				.inheritIO()
				.start()
				.waitFor()
		} catch (e: Exception) {
			System.err.println("omnisign: osascript notification failed: ${e.message}")
		}
	}
}

