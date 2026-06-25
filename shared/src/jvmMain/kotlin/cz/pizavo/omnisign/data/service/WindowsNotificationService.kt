package cz.pizavo.omnisign.data.service

import kotlin.io.encoding.Base64

/**
 * [OsNotificationService] for Windows using the WinRT Toast API via PowerShell.
 *
 * Uses `[Windows.UI.Notifications.ToastNotificationManager]`, available on Windows 8+ without any
 * third-party dependency. Two Windows-specific quirks shape how the toast is built and launched:
 *
 * - The title and body are embedded directly into the toast XML as element text, instead of being
 * added afterwards through the DOM. Appending to the live node list returned by
 * `GetElementsByTagName` throws a "collection was modified" error at runtime.
 * - The script is handed to PowerShell through `-EncodedCommand` (Base64 of its UTF-16LE bytes)
 * rather than `-Command`. Under `-Command` the shell mangles the quoting around the inline XML, so
 * `LoadXml` is handed a bare, unparseable `<toast>` literal. Encoding bypasses shell tokenisation;
 * no `-ExecutionPolicy Bypass` is needed because nothing is read from a file.
 *
 * CRITICAL urgency sets the toast scenario to "alarm" so it persists until dismissed.
 */
class WindowsNotificationService : OsNotificationService {
	
	override fun notify(title: String, body: String, urgency: NotificationUrgency) {
		try {
			val scenario = if (urgency == NotificationUrgency.CRITICAL) "alarm" else "default"
			val toastXml = "<toast scenario='$scenario'><visual><binding template='ToastGeneric'>" +
				"<text>${xmlEscape(title)}</text><text>${xmlEscape(body)}</text>" +
				"</binding></visual></toast>"
			val psXmlLiteral = toastXml.replace("'", "''")
			val script = $$"""
				[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
				[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
				$xml = [Windows.Data.Xml.Dom.XmlDocument]::new()
				$xml.LoadXml('$$psXmlLiteral')
				$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
				[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('omnisign').Show($toast)
			""".trimIndent()
			val encoded = Base64.Default.encode(script.toByteArray(Charsets.UTF_16LE))
			ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
				.inheritIO()
				.start()
				.waitFor()
		} catch (e: Exception) {
			System.err.println("omnisign: Windows toast notification failed: ${e.message}")
		}
	}

	/**
	 * Escape a user-supplied string for inclusion as XML element text in the toast payload:
	 * `&`, `<` and `>` become entity references. `&` is replaced first so the ampersands introduced
	 * by the later two replacements are not themselves re-escaped.
	 */
	private fun xmlEscape(value: String): String =
		value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

