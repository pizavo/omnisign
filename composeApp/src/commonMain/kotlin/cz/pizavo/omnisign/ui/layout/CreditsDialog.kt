package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Dialog
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.ui.model.ThirdPartyComponent
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.isWebPlatform
import kotlinx.serialization.json.Json
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Compose resource path of the generated credits list. */
private const val CreditsResourcePath = "files/third-party-credits.json"

/** Web location of the full, generated third-party notices. */
private const val CreditsNoticesUrl = "https://github.com/pizavo/omnisign/blob/main/THIRD-PARTY.md"

/** Lenient reader, so a future field added by the generator cannot break the dialog. */
private val CreditsJson = Json { ignoreUnknownKeys = true }

/** Surface tag identifying the package this build ships in, used to filter the credits. */
private val currentSurface: String
    get() = if (isWebPlatform()) "web" else "desktop"

/**
 * Dialog listing every third-party component distributed with OmniSign, grouped by licence.
 *
 * The list is read at runtime from [CreditsResourcePath], which the root
 * `:generateThirdPartyNotices` task generates, so the dialog cannot drift from what the
 * build actually ships. Desktop and web share that one file but ship very different
 * dependency sets — the web bundle contains none of the JVM signing stack and instead
 * carries npm packages the desktop build never sees — so entries are filtered to the
 * surface this build actually runs on.
 *
 * Beyond crediting the authors, this dialog is what discharges the runtime-notice duty of
 * the weak-copyleft licences OmniSign depends on: the GNU LGPL v2.1 requires a program that
 * displays copyright notices — which the Help panel does — to name the library, show its
 * copyright among them, and point the user at the licence text. Each entry therefore shows
 * the component's copyright and the file holding the licence's full text, all of which are
 * installed alongside the application.
 *
 * @param onDismiss Invoked to close the dialog.
 */
@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    var components by remember { mutableStateOf<List<ThirdPartyComponent>?>(null) }

    LaunchedEffect(Unit) {
        components = runCatching {
            CreditsJson.decodeFromString<List<ThirdPartyComponent>>(
                Res.readBytes(CreditsResourcePath).decodeToString(),
            ).filter { currentSurface in it.surfaces }
        }.getOrElse { emptyList() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 560.dp, max = 820.dp).heightIn(min = 420.dp, max = 720.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CreditsHeader(onClose = onDismiss)
            HorizontalDivider()

            val loaded = components
            when {
                loaded == null -> CreditsMessage(stringResource(Res.string.credits_loading))
                loaded.isEmpty() -> CreditsMessage(stringResource(Res.string.credits_unavailable))
                else -> CreditsList(components = loaded, modifier = Modifier.weight(1f))
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.credits_licenses_location),
                    style = LumoTheme.typography.body3,
                    color = LumoTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                ExternalLink(
                    text = stringResource(Res.string.credits_full_notices),
                    url = CreditsNoticesUrl,
                )
            }
        }
    }
}

/** Header row with the dialog title and close button, matching the other lumo dialogs. */
@Composable
private fun CreditsHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.icon_scale),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = LumoTheme.colors.textSecondary,
            )
            Text(text = stringResource(Res.string.credits_title), style = LumoTheme.typography.h3)
        }
        IconButton(variant = IconButtonVariant.Ghost, onClick = onClose) {
            Icon(
                painter = painterResource(Res.drawable.icon_x),
                contentDescription = stringResource(Res.string.action_close),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Centred single-line message used while the list loads or when it cannot be read. */
@Composable
private fun CreditsMessage(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = text, style = LumoTheme.typography.body2, color = LumoTheme.colors.textSecondary)
    }
}

/**
 * Scrollable body: an explanatory lead-in, then one section per licence listing the
 * components used under it.
 *
 * Uses [VerticalScrollableColumn] rather than a `LazyColumn` so the desktop target gets the
 * app's themed scrollbar track — the list is a few dozen rows, well within what a plain
 * scrolling column handles, and without a visible track there is nothing to signal that the
 * majority of the components are below the fold.
 *
 * @param components Every bundled component, in the order the generator emitted them.
 * @param modifier Modifier applied to the scrolling container.
 */
@Composable
private fun CreditsList(components: List<ThirdPartyComponent>, modifier: Modifier = Modifier) {
    val grouped: List<Pair<String, List<ThirdPartyComponent>>> = remember(components) {
        components.groupBy { it.licenseName }
            .map { (licenseName, entries) -> licenseName to entries }
            .sortedBy { it.first }
    }

    VerticalScrollableColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(Res.string.credits_intro),
            style = LumoTheme.typography.body3,
            color = LumoTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        grouped.forEach { (licenseName, entries) ->
            Column(modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) {
                Text(
                    text = licenseName,
                    style = LumoTheme.typography.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.credits_license_file, entries.first().licenseText),
                    style = LumoTheme.typography.body3,
                    color = LumoTheme.colors.textSecondary,
                )
            }
            entries.forEach { component -> CreditsRow(component) }
        }
    }
}

/**
 * One component: its name, artifact count, copyright line and homepage link.
 *
 * @param component The component to render.
 */
@Composable
private fun CreditsRow(component: ThirdPartyComponent) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = component.name,
                style = LumoTheme.typography.body2,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    Res.string.credits_artifact_count,
                    component.artifacts.toString(),
                ),
                style = LumoTheme.typography.body3,
                color = LumoTheme.colors.textSecondary,
            )
        }
        component.copyright?.let {
            Text(text = it, style = LumoTheme.typography.body3, color = LumoTheme.colors.textSecondary)
        }
        component.homepage?.let {
            ExternalLink(text = it, url = it)
        }
    }
}
