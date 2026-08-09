package cz.pizavo.omnisign.ui.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One third-party component bundled with OmniSign, as listed in the Credits dialog.
 *
 * Instances are deserialized from `composeResources/files/third-party-credits.json`,
 * which the root `:generateThirdPartyNotices` task generates from the resolved runtime
 * classpaths and the curated facts in `gradle/third-party-licenses.json`. The file is
 * never edited by hand, so this class must stay in step with the generator's output.
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
 *   `desktop` and `web`. The desktop and web builds share one credits file but ship very
 *   different dependency sets, so each filters the list down to its own surface rather than
 *   crediting libraries it does not distribute.
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
