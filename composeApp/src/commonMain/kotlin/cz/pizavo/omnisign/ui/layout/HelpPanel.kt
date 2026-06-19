package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.BuildConfig
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.HorizontalDivider
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.Switch
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.TooltipPlacement
import cz.pizavo.omnisign.lumo.components.rememberTooltipPositionProvider
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.ui.platform.exportSupportLogArchive
import cz.pizavo.omnisign.ui.platform.isExtendedLoggingEnabled
import cz.pizavo.omnisign.ui.platform.isSupportLogAvailable
import cz.pizavo.omnisign.ui.platform.openSupportLogDirectory
import cz.pizavo.omnisign.ui.platform.setDebugLoggingEnabled
import cz.pizavo.omnisign.ui.platform.setExtendedLoggingEnabled
import cz.pizavo.omnisign.ui.toast.LocalToastService
import cz.pizavo.omnisign.ui.toast.ToastMessage
import kotlinx.coroutines.launch
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Public source repository the GitHub action button opens. */
private const val HelpRepositoryUrl = "https://github.com/pizavo/omnisign"

/** Hosted Docusaurus documentation site the Documentation button opens. */
private const val HelpDocumentationUrl = "https://pizavo.github.io/omnisign/"

/**
 * Prefilled GitHub "new issue" URL: pre-selects the `bug` and `desktop` labels
 * (they must already exist in the repository for GitHub to apply them) and
 * seeds a body asking the reporter to attach the exported log archive — GitHub
 * has no URL parameter to attach files automatically.
 */
private const val HelpIssueUrl =
    "https://github.com/pizavo/omnisign/issues/new?labels=bug%2Cdesktop&body=Describe%20the%20issue%20here.%0A%0APlease%20attach%20the%20exported%20log%20archive."

/** Canonical text of the license under which OmniSign is distributed. */
private const val HelpLicenseUrl = "https://www.gnu.org/licenses/agpl-3.0.html"

/** Human-readable name of the project license, mirrored from `LICENSE.md`. */
private const val HelpLicenseName = "GNU AGPL v3.0 or later"

/** Copyright/author line, kept in sync with `LICENSE.md` and the packaging metadata. */
private const val HelpAuthorLine = "© 2026 Pizavo"

/**
 * Help panel body rendered inside the right-hand [IslandSidePanel].
 *
 * Shows the source repository and online documentation as icon buttons at the
 * top and pins the license, build version and author to the bottom of the
 * panel. Between them, on the desktop target only, a Support section exposes
 * log export and debug-logging controls — it is hidden on web, where
 * [isSupportLogAvailable] is `false`. External links open through
 * [LocalUriHandler] so the panel works unchanged on desktop (JVM) and web
 * (Wasm).
 *
 * Emitted as a [ColumnScope] extension into the host [IslandSidePanel]'s
 * viewport-filling body; the weighted spacer keeps the license/footer group at
 * the bottom when there is room and lets the panel scroll when there is not.
 *
 * @param debugLoggingEnabled Hoisted debug-logging state, so the sidebar can
 *   show an active indicator that updates the moment the toggle changes.
 * @param onDebugLoggingChange Invoked with the new state when the user toggles
 *   debug logging.
 */
@Composable
fun ColumnScope.HelpPanel(
    debugLoggingEnabled: Boolean,
    onDebugLoggingChange: (Boolean) -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HelpActionButton(
            label = stringResource(Res.string.help_action_github_repository),
            icon = Res.drawable.icon_github,
            contentDescription = stringResource(Res.string.help_cd_open_github_repository),
            onClick = { uriHandler.openUri(HelpRepositoryUrl) },
        )
        HelpActionButton(
            label = stringResource(Res.string.help_action_documentation),
            icon = Res.drawable.icon_file_text,
            contentDescription = stringResource(Res.string.help_cd_open_documentation),
            onClick = { uriHandler.openUri(HelpDocumentationUrl) },
        )
    }

    if (remember { isSupportLogAvailable() }) {
        HelpSupportSection(
            debugEnabled = debugLoggingEnabled,
            onDebugChange = onDebugLoggingChange,
        )
    }

    Spacer(modifier = Modifier.weight(1f))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.icon_scale),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = LumoTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.help_label_license),
            style = LumoTheme.typography.label1,
            modifier = Modifier.weight(1f),
        )
        ExternalLink(
            text = HelpLicenseName,
            url = HelpLicenseUrl,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.help_label_version, BuildConfig.VERSION),
            style = LumoTheme.typography.body3,
            color = LumoTheme.colors.textSecondary,
        )
        Text(
            text = HelpAuthorLine,
            style = LumoTheme.typography.body3,
            color = LumoTheme.colors.textSecondary,
        )
    }
}

/**
 * Icon-only outlined button used for the Help panel's external-link actions
 * (repository, documentation). The [label] is surfaced as a hover tooltip
 * instead of inline text, mirroring the sidebar icon affordance.
 *
 * @param label Tooltip text shown on hover.
 * @param icon Drawable resource rendered inside the button.
 * @param contentDescription Accessibility description for [icon].
 * @param onClick Invoked when the button is pressed.
 */
@Composable
private fun HelpActionButton(
    label: String,
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipPlacement.Top),
        tooltip = { Tooltip { Text(text = label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            variant = IconButtonVariant.PrimaryOutlined,
            onClick = onClick,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Desktop-only Support section: export the diagnostic log archive, reveal the
 * log folder, and toggle debug logging — applied immediately and persisted
 * across restarts. Rendered only when [isSupportLogAvailable] is `true`; the
 * web target has no log file or Logback backend.
 *
 * @param debugEnabled Hoisted debug-logging state (drives the toggle and the
 *   visibility of the extended-logging row).
 * @param onDebugChange Invoked with the new state when debug logging is toggled.
 */
@Composable
private fun HelpSupportSection(
    debugEnabled: Boolean,
    onDebugChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToastService.current
    val openFolderFailedMessage = stringResource(Res.string.help_toast_open_folder_failed)
    var extended by remember { mutableStateOf(isExtendedLoggingEnabled()) }

    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(Res.string.help_section_support), style = LumoTheme.typography.label1)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(Res.string.help_support_description),
        style = LumoTheme.typography.body3,
        color = LumoTheme.colors.textSecondary,
    )

    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HelpActionButton(
            label = stringResource(Res.string.help_action_export_logs),
            icon = Res.drawable.icon_download,
            contentDescription = stringResource(Res.string.help_cd_export_logs),
            onClick = {
                scope.launch {
                    val exported = exportSupportLogArchive()
                    toast?.show(
                        ToastMessage(getString(if (exported) Res.string.help_toast_logs_exported else Res.string.help_toast_logs_cancelled)),
                    )
                }
            },
        )
        HelpActionButton(
            label = stringResource(Res.string.help_action_open_folder),
            icon = Res.drawable.icon_folder,
            contentDescription = stringResource(Res.string.help_cd_open_log_folder),
            onClick = {
                if (!openSupportLogDirectory()) {
                    toast?.show(ToastMessage(openFolderFailedMessage))
                }
            },
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        ExternalLink(text = stringResource(Res.string.help_action_report_issue), url = HelpIssueUrl)
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(Res.string.help_support_attach_logs),
        style = LumoTheme.typography.body3,
        color = LumoTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(12.dp))
    SupportToggleRow(
        title = stringResource(Res.string.help_toggle_debug_logging_title),
        caption = stringResource(Res.string.help_toggle_debug_logging_caption),
        checked = debugEnabled,
        onCheckedChange = {
            setDebugLoggingEnabled(it)
            onDebugChange(it)
        },
    )

    if (debugEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        SupportToggleRow(
            title = stringResource(Res.string.help_toggle_extended_logs_title),
            caption = stringResource(Res.string.help_toggle_extended_logs_caption),
            checked = extended,
            onCheckedChange = {
                extended = it
                setExtendedLoggingEnabled(it)
            },
        )
    }
}

/**
 * Labelled row pairing a title and caption with a trailing [Switch], used for
 * the Support section's verbosity toggles.
 *
 * @param title Setting name.
 * @param caption Secondary explanatory line beneath the title.
 * @param checked Current switch state.
 * @param onCheckedChange Invoked with the new state when the switch is toggled.
 */
@Composable
private fun SupportToggleRow(
    title: String,
    caption: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = LumoTheme.typography.label1)
            Text(
                text = caption,
                style = LumoTheme.typography.body3,
                color = LumoTheme.colors.textSecondary,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
