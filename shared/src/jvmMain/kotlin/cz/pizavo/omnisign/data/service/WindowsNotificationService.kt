package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.data.repository.appConfigDirectory
import kotlin.io.encoding.Base64
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.outputStream

/**
 * [OsNotificationService] for Windows using the WinRT Toast API via PowerShell.
 *
 * Uses `[Windows.UI.Notifications.ToastNotificationManager]`, available on Windows 8+ without any
 * third-party dependency. Three Windows-specific quirks shape how the toast is built and launched:
 *
 * - The app's [APP_USER_MODEL_ID] is registered under `HKCU\Software\Classes\AppUserModelId` with a
 * `DisplayName` and an `IconUri` before the toast is shown. Without a registered identity Windows
 * accepts the toast silently (no error) but never displays it; the registration is also what brands
 * the toast and its Action Center entry with the OmniSign name and icon (extracted from a bundled
 * resource to [appConfigDirectory], because `IconUri` needs a real file path).
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
			val iconRegistration = toastIconPath()?.let { path ->
				"New-ItemProperty -Path \$aumidKey -Name IconUri -Value '${path.replace("'", "''")}' -PropertyType String -Force | Out-Null"
			}.orEmpty()
			val script = $$"""
				$aumidKey = 'HKCU:\Software\Classes\AppUserModelId\$$APP_USER_MODEL_ID'
				if (-not (Test-Path $aumidKey)) {
					New-Item -Path $aumidKey -Force | Out-Null
					New-ItemProperty -Path $aumidKey -Name DisplayName -Value '$$DISPLAY_NAME' -PropertyType String -Force | Out-Null
					$$iconRegistration
				}
				[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
				[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
				$xml = [Windows.Data.Xml.Dom.XmlDocument]::new()
				$xml.LoadXml('$$psXmlLiteral')
				$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
				[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$$APP_USER_MODEL_ID').Show($toast)
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

	/**
	 * Ensure the bundled toast icon exists on disk and return its absolute path, or `null` when it
	 * cannot be provided.
	 *
	 * The Windows toast `IconUri` needs a real file path, but the icon ships as a classpath resource,
	 * so it is extracted once into [appConfigDirectory] and reused thereafter. Any failure degrades
	 * to `null`, leaving the toast to show without the branded icon rather than failing outright.
	 */
	private fun toastIconPath(): String? =
		try {
			val target = appConfigDirectory().resolve(ICON_FILE_NAME)
			if (target.notExists()) {
				target.parent?.createDirectories()
				(javaClass.getResourceAsStream(ICON_RESOURCE) ?: return null).use { input ->
					target.outputStream().use { output -> input.copyTo(output) }
				}
			}
			target.absolutePathString()
		} catch (e: Exception) {
			null
		}

	companion object {
		/**
		 * Application User Model ID the toast is shown under. Registered under
		 * `HKCU\Software\Classes\AppUserModelId` so Windows treats it as a notifiable identity.
		 */
		private const val APP_USER_MODEL_ID = "cz.pizavo.omnisign"

		/** Human-readable source name shown for the toast in the Action Center. */
		private const val DISPLAY_NAME = "OmniSign"

		/** Classpath location of the bundled toast icon (a 512px PNG). */
		private const val ICON_RESOURCE = "/omnisign-toast-icon.png"

		/** File name [ICON_RESOURCE] is extracted to under the config directory. */
		private const val ICON_FILE_NAME = "omnisign-toast-icon.png"
	}
}

