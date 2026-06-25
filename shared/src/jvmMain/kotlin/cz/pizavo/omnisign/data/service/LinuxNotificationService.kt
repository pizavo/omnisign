package cz.pizavo.omnisign.data.service

/**
 * [OsNotificationService] for Linux using `notify-send` (libnotify).
 *
 * Urgency maps directly to `notify-send --urgency low|normal|critical`.
 *
 * Renewal is scheduled by [SystemdSchedulerService] as a systemd **user** timer, so the renewal
 * process runs under the user's systemd manager — which on modern desktops shares the session bus,
 * letting `notify-send` reach the notification daemon while a graphical session is active. If
 * `notify-send` is missing, or no notification daemon is running (a headless login, or the timer
 * firing while no desktop session is active), the call fails; the error is printed to stderr and
 * swallowed so it never aborts the renewal.
 *
 * On hosts without systemd, where renewal is wired to another scheduler, that scheduler must expose
 * the user's `DBUS_SESSION_BUS_ADDRESS` for notifications to be delivered.
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

