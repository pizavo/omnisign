package cz.pizavo.omnisign.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.ui.platform.LocalAppDateFormat
import cz.pizavo.omnisign.ui.platform.LocalAppLocale
import io.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import java.util.TimeZone

/**
 * Renders [content] in the app's theme and captures it with Roborazzi as `<name>.png`.
 *
 * Whether anything is captured, and where the image goes, depends on the `-Pscreenshots` and `-PscreenshotDir`
 * properties of the `jvmTest` task; without them the call only checks that [content] composes. The content is
 * rendered on the theme's background, in a scene of a fixed size at density 1, in English, with dates in UTC, so the
 * same machine renders the same image on every run. The default locale and time zone are restored afterwards.
 *
 * @param name The image's file name, without the extension.
 * @param darkTheme Whether to render the dark theme.
 * @param width The width of the scene, in pixels.
 * @param height The height of the scene, in pixels.
 * @param interaction What to do with the rendered content before it is captured, such as clicking a section open.
 * @param content The composable to capture.
 */
@OptIn(ExperimentalTestApi::class)
fun captureScreenshot(
	name: String,
	darkTheme: Boolean = false,
	width: Int = 420,
	height: Int = 720,
	interaction: ComposeUiTest.() -> Unit = {},
	content: @Composable () -> Unit,
) {
	val locale = Locale.getDefault()
	val timeZone = TimeZone.getDefault()
	TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
	try {
		runDesktopComposeUiTest(width = width, height = height) {
			setContent {
				CompositionLocalProvider(
					LocalAppLocale provides "en",
					LocalAppDateFormat provides DateFormat.SYSTEM,
				) {
					LumoTheme(isDarkTheme = darkTheme) {
						Box(Modifier.fillMaxSize().background(LumoTheme.colors.background).padding(12.dp)) {
							content()
						}
					}
				}
			}
			interaction()
			onRoot().captureRoboImage("$name.png")
		}
	} finally {
		Locale.setDefault(locale)
		TimeZone.setDefault(timeZone)
	}
}
