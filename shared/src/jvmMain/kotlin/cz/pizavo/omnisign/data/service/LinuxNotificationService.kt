package cz.pizavo.omnisign.data.service

/**
 * [OsNotificationService] for Linux using `notify-send` (libnotify).
 *
 * Urgency maps directly to `notify-send --urgency low|normal|critical`.
 * If `notify-send` is not installed or fails (e.g. no DBUS session when run from cron),
 * the error is printed to stderr and silently swallowed.
 *
 * To make `notify-send` work from a cron job the `DBUS_SESSION_BUS_ADDRESS` environment
 * variable must be set. On systemd-based desktops this is typically available via
 * `systemctl --user` timers; for classic cron the installer can document the workaround
 * of exporting the variable in the crontab.
 */
class LinuxNotificationService : OsNotificationService {
	
	override fun notify(title: String, body: String, urgency: NotificationUrgency) {
		try {
			ProcessBuilder(
				"notify-send",
				"--urgency", urgency.toLinux(),
				"--app-name", APP_NAME,
				title,
				body,
			).inheritIO().start().waitFor()
		} catch (e: Exception) {
			System.err.println("omnisign: notify-send failed: ${e.message}")
		}
	}
	
	private fun NotificationUrgency.toLinux() = when (this) {
		NotificationUrgency.LOW -> "low"
		NotificationUrgency.NORMAL -> "normal"
		NotificationUrgency.CRITICAL -> "critical"
	}
	
	private companion object {
		const val APP_NAME = "omnisign"
	}
}

