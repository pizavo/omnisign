package cz.pizavo.omnisign.data.service

/**
 * Sends a native desktop notification to the currently logged-in user.
 *
 * Implementations use the platform's CLI notification tool so that no additional
 * library dependency is required:
 * - **Linux**: `notify-send` (libnotify)
 * - **macOS**: `osascript` with `display notification`
 * - **Windows**: PowerShell `[Windows.UI.Notifications.ToastNotificationManager]`
 *
 * Notifications are fire-and-forget: failures to deliver are logged to stderr but
 * never propagate as exceptions, so a missing notification daemon can never abort
 * a renewal run.
 */
interface OsNotificationService {
	/**
	 * Send a desktop notification.
	 *
	 * @param title Short title line shown in bold by most notification centres.
	 * @param body  Longer description body. May be truncated by the OS.
	 * @param urgency Hint to the OS about how prominently to surface the notification.
	 */
	fun notify(title: String, body: String, urgency: NotificationUrgency = NotificationUrgency.NORMAL)
}

/**
 * Maps to the urgency levels supported across all three platforms.
 */
enum class NotificationUrgency {
	/** Purely informational — may be shown silently or suppressed in Do-Not-Disturb. */
	LOW,
	
	/** Default importance. */
	NORMAL,
	
	/** Critical — bypasses Do-Not-Disturb on supported platforms. */
	CRITICAL,
}

