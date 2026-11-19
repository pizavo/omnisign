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
 * third-party dependency. Four Windows-specific quirks shape how the toast is built and launched:
 *
 * - The app's [APP_USER_MODEL_ID] is registered under `HKCU\Software\Classes\AppUserModelId` with a
 * `DisplayName` and an `IconUri` before the toast is shown. Without a registered identity Windows
 * accepts the toast silently (no error) but never displays it; the registration is also what brands
 * the toast and its Action Center entry with the OmniSign name and icon (extracted from a bundled
 * resource to [appConfigDirectory], because `IconUri` needs a real file path).
 * - The title and body are embedded directly into the toast XML as element text, instead of being
 * added afterwards through the DOM. Appending to the live node list returned by
 * `GetElementsByTagName` throws a "collection was modified" error at runtime.
 * - The toast XML and the icon's path reach PowerShell in the environment variables
 * [TOAST_XML_VARIABLE] and [ICON_PATH_VARIABLE], never in the script, which is [SCRIPT] for every
 * toast. PowerShell therefore never reads a job name, an error message or a path as code. Spliced
 * into a single-quoted PowerShell string, any of them could end it early: PowerShell ends such a
 * string at the typographic quotes U+2018 to U+201B as well as at the apostrophe, so a job named
 * `Jan’s contracts` broke the script and its toast never showed, and a crafted name, which an
 * imported configuration archive can carry, ran the text after the quote as commands.
 * - The script is handed to PowerShell through `-EncodedCommand` (Base64 of its UTF-16LE bytes)
 * rather than `-Command`, whose parsing of the command line strips and mangles quotes. Encoding
 * bypasses that parsing altogether; no `-ExecutionPolicy Bypass` is needed because nothing is read
 * from a file.
 *
 * CRITICAL urgency sets the toast scenario to "alarm" so it persists until dismissed.
 */
class WindowsNotificationService : OsNotificationService {

	override fun notify(title: String, body: String, urgency: NotificationUrgency) {
		try {
			toastProcess(title, body, urgency, toastIconPath())
				.inheritIO()
				.start()
				.waitFor()
		} catch (e: Exception) {
			System.err.println("omnisign: Windows toast notification failed: ${e.message}")
		}
	}

	/**
	 * The PowerShell process that shows a toast of [title] and [body], not yet started.
	 *
	 * It runs [SCRIPT], the same script for every toast. The toast's XML travels in
	 * [TOAST_XML_VARIABLE], and [iconPath] in [ICON_PATH_VARIABLE], which is empty when [iconPath]
	 * is `null` whatever this process's own environment holds.
	 *
	 * @param urgency Urgency of the toast; [NotificationUrgency.CRITICAL] shows it in the "alarm"
	 * scenario.
	 * @param iconPath Absolute path of the icon registered with the app's identity when a toast is
	 * shown for the first time, or `null` to register none.
	 * @return The process, for [notify] to start.
	 */
	internal fun toastProcess(
		title: String,
		body: String,
		urgency: NotificationUrgency,
		iconPath: String?,
	): ProcessBuilder =
		ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", ENCODED_SCRIPT).apply {
			environment()[TOAST_XML_VARIABLE] = toastXml(title, body, urgency)
			environment()[ICON_PATH_VARIABLE] = iconPath.orEmpty()
		}

	/**
	 * The toast's XML: [title] and [body] as the text of its two lines, escaped by [xmlEscape], in
	 * the "alarm" scenario when [urgency] is [NotificationUrgency.CRITICAL] and in the default one
	 * otherwise.
	 */
	private fun toastXml(title: String, body: String, urgency: NotificationUrgency): String {
		val scenario = if (urgency == NotificationUrgency.CRITICAL) "alarm" else "default"
		return "<toast scenario='$scenario'><visual><binding template='ToastGeneric'>" +
			"<text>${xmlEscape(title)}</text><text>${xmlEscape(body)}</text>" +
			"</binding></visual></toast>"
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

	/**
	 * The app's toast identity and icon, and the script that shows a toast with the environment
	 * variables it reads.
	 */
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

		/** Environment variable that hands [SCRIPT] the toast's XML. */
		internal const val TOAST_XML_VARIABLE = "OMNISIGN_TOAST_XML"

		/** Environment variable that hands [SCRIPT] the toast icon's path, empty when there is no icon. */
		internal const val ICON_PATH_VARIABLE = "OMNISIGN_TOAST_ICON"

		/**
		 * The lines of [SCRIPT] that load the toast's XML from [TOAST_XML_VARIABLE] into `$xml`, a
		 * WinRT XML document. They neither write to the registry nor show anything.
		 */
		internal val LOAD_TOAST_XML: String = $$"""
			[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
			$xml = [Windows.Data.Xml.Dom.XmlDocument]::new()
			$xml.LoadXml($env:$$TOAST_XML_VARIABLE)
		""".trimIndent()

		/**
		 * The script that shows a toast. It registers the app's identity the first time, with the icon
		 * whose path [ICON_PATH_VARIABLE] holds unless it is empty, loads the toast's XML with
		 * [LOAD_TOAST_XML] and shows the toast. It holds no text of the toast.
		 */
		internal val SCRIPT: String = listOf(
			$$"""
				$aumidKey = 'HKCU:\Software\Classes\AppUserModelId\$$APP_USER_MODEL_ID'
				if (-not (Test-Path $aumidKey)) {
					New-Item -Path $aumidKey -Force | Out-Null
					New-ItemProperty -Path $aumidKey -Name DisplayName -Value '$$DISPLAY_NAME' -PropertyType String -Force | Out-Null
					if ($env:$$ICON_PATH_VARIABLE) {
						New-ItemProperty -Path $aumidKey -Name IconUri -Value $env:$$ICON_PATH_VARIABLE -PropertyType String -Force | Out-Null
					}
				}
				[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
			""".trimIndent(),
			LOAD_TOAST_XML,
			$$"""
				$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
				[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$$APP_USER_MODEL_ID').Show($toast)
			""".trimIndent(),
		).joinToString("\n")

		/** [SCRIPT] as PowerShell's `-EncodedCommand` takes it: Base64 of its UTF-16LE bytes. */
		private val ENCODED_SCRIPT: String = Base64.Default.encode(SCRIPT.toByteArray(Charsets.UTF_16LE))
	}
}
