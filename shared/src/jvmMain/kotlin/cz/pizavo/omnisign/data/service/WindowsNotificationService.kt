package cz.pizavo.omnisign.data.service

/**
 * [OsNotificationService] for Windows using the WinRT Toast API via PowerShell.
 *
 * Uses `[Windows.UI.Notifications.ToastNotificationManager]` which is available on
 * Windows 8+ without any third-party dependency. The script is kept minimal so it
 * works in constrained execution environments (no `-ExecutionPolicy Bypass` flag
 * is needed because the script is passed inline, not from a file).
 *
 * CRITICAL urgency sets the toast scenario to "alarm" so it persists until dismissed.
 */
class WindowsNotificationService : OsNotificationService {
	
	override fun notify(title: String, body: String, urgency: NotificationUrgency) {
		try {
			val scenario = if (urgency == NotificationUrgency.CRITICAL) "alarm" else "default"
			val escaped = body.replace("\"", "'").replace("<", "&lt;").replace(">", "&gt;")
			val titleEscaped = title.replace("\"", "'").replace("<", "&lt;").replace(">", "&gt;")
			val script = $$"""
                $app = 'omnisign'
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
                $xml = [Windows.Data.Xml.Dom.XmlDocument]::new()
                $xml.LoadXml("<toast scenario='$$scenario'><visual><binding template='ToastGeneric'><text></text><text></text></binding></visual></toast>")
                $xml.GetElementsByTagName('text')[0].AppendChild($xml.CreateTextNode('$$titleEscaped')) | Out-Null
                $xml.GetElementsByTagName('text')[1].AppendChild($xml.CreateTextNode('$$escaped')) | Out-Null
                $toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
                [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show($toast)
            """.trimIndent()
			ProcessBuilder("powershell", "-NonInteractive", "-Command", script)
				.inheritIO()
				.start()
				.waitFor()
		} catch (e: Exception) {
			System.err.println("omnisign: Windows toast notification failed: ${e.message}")
		}
	}
}

