package cz.pizavo.omnisign.legal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One third-party component distributed with OmniSign, as credited to the user at runtime.
 *
 * Instances are deserialized from the generated `third-party-credits.json`, which the root
 * `:generateThirdPartyNotices` task writes from the resolved runtime classpaths and the curated
 * facts in `gradle/third-party-licenses.json`. The file is never edited by hand, so this class
 * must stay in step with the generator's output.
 *
 * The same shape backs all four surfaces: the desktop and web Credits dialog reads it from the
 * packaged Compose resource, while the CLI's `credits` command and the server's
 * `GET /api/v1/credits` read the copy carried on the JVM classpath. It lives in `commonMain` so
 * a client deserializing the HTTP response references the exact same type the server produced.
 *
 * @property name Human-readable project name, for example `EU DSS (Digital Signature Services)`.
 * @property licenseId SPDX-style identifier of the licence the component is used under.
 * @property licenseName Full licence name as shown to the user.
 * @property licenseText File name of the licence's full text inside the `licenses/` directory
 *   that ships with the application.
 * @property copyright Copyright line, absent when the component states none.
 * @property homepage Project homepage, which doubles as the source-code location required by
 *   the weak-copyleft licences.
 * @property surfaces Packages that actually contain this component: any of `cli`, `server`,
 *   `desktop` and `web`. All four share one credits file but ship very different dependency
 *   sets, so each filters the list down to its own surface rather than crediting libraries it
 *   does not distribute. Kept as plain strings rather than an enum so that a surface added by a
 *   future generator cannot fail the whole parse and blank out the credits.
 * @property artifacts Number of individual artifacts (jars or npm packages) this component
 *   contributes.
 */
@Serializable
data class ThirdPartyComponent(
    @SerialName("name") val name: String,
    @SerialName("licenseId") val licenseId: String,
    @SerialName("licenseName") val licenseName: String,
    @SerialName("licenseText") val licenseText: String,
    @SerialName("copyright") val copyright: String? = null,
    @SerialName("homepage") val homepage: String? = null,
    @SerialName("surfaces") val surfaces: List<String> = emptyList(),
    @SerialName("artifacts") val artifacts: Int = 1,
)
