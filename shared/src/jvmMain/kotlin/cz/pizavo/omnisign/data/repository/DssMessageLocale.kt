package cz.pizavo.omnisign.data.repository

import java.util.Locale
import java.util.ResourceBundle

/**
 * The locale to give DSS for report messages asked for in [requested]: the locale of the
 * `dss-messages` translation that serves [requested], or [Locale.ROOT] when no translation does and
 * DSS's English base bundle has to.
 *
 * DSS looks its messages up with `ResourceBundle.getBundle` and the default lookup control. When no
 * translation serves the locale it is given, that control tries the JVM default locale before it
 * settles for the base bundle. On a machine whose default locale is Czech or Slovak, a report asked
 * for in English or German would then read in Czech or Slovak, from the translations OmniSign
 * bundles. The lookup made here leaves the default locale out, and DSS takes the locale it returns
 * as it is: a translation's own locale, such as `cs`, finds that translation, and [Locale.ROOT]
 * finds the base bundle without trying any other.
 *
 * The translations are found on the classpath, so one added later is served without a change here.
 *
 * @param requested The locale the report messages are asked for.
 */
internal fun dssMessageLocale(requested: Locale): Locale =
	ResourceBundle.getBundle(
		"dss-messages",
		requested,
		ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
	).locale
