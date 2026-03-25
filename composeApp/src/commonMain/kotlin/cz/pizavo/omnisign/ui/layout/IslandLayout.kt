package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.ui.model.PanelSide
import cz.pizavo.omnisign.ui.model.ProfileListState
import cz.pizavo.omnisign.ui.model.ProfilePanelMode
import cz.pizavo.omnisign.ui.model.SidePanel
import cz.pizavo.omnisign.ui.platform.loadPdfFromPlatformFile
import cz.pizavo.omnisign.ui.viewmodel.PdfViewerViewModel
import cz.pizavo.omnisign.ui.viewmodel.ProfileViewModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Root shell composable that implements the IntelliJ "Island" layout.
 *
 * The layout consists of:
 * - A seamless [IslandToolbar] at the top.
 * - A left [IslandSideBar] with icon buttons that toggle an [IslandSidePanel].
 * - A central [IslandContentCard] occupying the remaining space.
 * - A right [IslandSideBar] + [IslandSidePanel] pair mirroring the left side.
 *
 * Panel visibility is managed with local `remember` state — one nullable
 * [SidePanel] per side. Clicking an already-active icon collapses the panel;
 * clicking a different icon on the same side switches to that panel.
 *
 * The toolbar's folder icon triggers a platform file picker. The selected
 * PDF is rendered inside the central content card via [PdfViewerContent].
 *
 * @param isDarkTheme Whether a dark theme is currently active.
 * @param onToggleTheme Callback invoked when the user toggles the theme.
 * @param modifier Optional [Modifier] applied to the outermost container.
 */
@Composable
fun IslandLayout(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pdfViewModel: PdfViewerViewModel = viewModel { PdfViewerViewModel() }
    val pdfState by pdfViewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    val profileViewModel: ProfileViewModel? = remember {
        val koin = KoinPlatform.getKoinOrNull() ?: return@remember null
        ProfileViewModel(
            koin.get<ManageProfileUseCase>(),
            koin.get<GetConfigUseCase>(),
            koin.getOrNull<CredentialStore>(),
        )
    }
    val profileState by (profileViewModel?.state ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(ProfileListState())
    }).collectAsState()

    val filePickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("pdf")),
    ) { platformFile: PlatformFile? ->
        if (platformFile != null) {
            scope.launch {
                val document = loadPdfFromPlatformFile(platformFile)
                pdfViewModel.onDocumentLoaded(document)
            }
        }
    }

    val leftPanels = remember { SidePanel.entries.filter { it.side == PanelSide.Left } }
    val rightPanels = remember { SidePanel.entries.filter { it.side == PanelSide.Right } }

    var activeLeftPanel by remember { mutableStateOf<SidePanel?>(null) }
    var activeRightPanel by remember { mutableStateOf<SidePanel?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        IslandToolbar(
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            onOpenFile = { filePickerLauncher.launch() },
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IslandSideBar(
                panels = leftPanels,
                activePanel = activeLeftPanel,
                onPanelToggle = { panel ->
                    activeLeftPanel = if (activeLeftPanel == panel) null else panel
                },
            )

            IslandSidePanel(
                visible = activeLeftPanel != null,
                title = activeLeftPanel?.label ?: "",
                onClose = { activeLeftPanel = null },
                fromEnd = false,
                modifier = Modifier.fillMaxHeight(),
            ) {
                PanelPlaceholderContent(panel = activeLeftPanel)
            }

            IslandContentCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                PdfViewerContent(
                    state = pdfState,
                    onPreviousPage = pdfViewModel::previousPage,
                    onNextPage = pdfViewModel::nextPage,
                    onZoomIn = pdfViewModel::zoomIn,
                    onZoomOut = pdfViewModel::zoomOut,
                    onResetZoom = pdfViewModel::resetZoom,
                )
            }

            val isEditingProfile = activeRightPanel == SidePanel.Profiles &&
                    profileState.mode is ProfilePanelMode.Editing
            val rightPanelTitle = if (isEditingProfile) "Edit Profile"
                else activeRightPanel?.label ?: ""

            IslandSidePanel(
                visible = activeRightPanel != null,
                title = rightPanelTitle,
                onClose = {
                    if (isEditingProfile) profileViewModel?.cancelEdit()
                    activeRightPanel = null
                },
                fromEnd = true,
                onBack = if (isEditingProfile) {
                    { profileViewModel?.cancelEdit() }
                } else null,
                modifier = Modifier.fillMaxHeight(),
            ) {
                when (activeRightPanel) {
                    SidePanel.Profiles -> ProfilesPanel(
                        state = profileState,
                        onToggleActive = { profileViewModel?.toggleActive(it) },
                        onEdit = { profileViewModel?.startEdit(it) },
                        onDelete = { profileViewModel?.delete(it) },
                        onAdd = { profileViewModel?.startCreate() },
                        onDeselectActive = { profileViewModel?.deselectActive() },
                        onConfirmCreate = { profileViewModel?.confirmCreate(it) },
                        onCancelCreate = { profileViewModel?.cancelCreate() },
                        onFieldChange = { transform -> profileViewModel?.updateEditState(transform) },
                        onSaveEdit = { profileViewModel?.saveEdit() },
                    )
                    else -> PanelPlaceholderContent(panel = activeRightPanel)
                }
            }

            IslandSideBar(
                panels = rightPanels,
                activePanel = activeRightPanel,
                onPanelToggle = { panel ->
                    activeRightPanel = if (activeRightPanel == panel) null else {
                        if (panel == SidePanel.Profiles) profileViewModel?.refresh()
                        panel
                    }
                },
            )
        }
    }
}

/**
 * Temporary placeholder content rendered inside an [IslandSidePanel].
 *
 * Displays a short description of the panel's purpose. Will be replaced by
 * dedicated per-panel composables (e.g. `SignPanel`, `ValidatePanel`) in the future.
 *
 * @param panel The currently active [SidePanel], or `null` if the panel is collapsing.
 */
@Composable
private fun PanelPlaceholderContent(panel: SidePanel?) {
    when (panel) {
        SidePanel.Signature -> Text(
            text = "Signature details and metadata will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Sign -> Text(
            text = "Signing operations will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Validate -> Text(
            text = "Validation results will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Archive -> Text(
            text = "Archival and re-timestamping controls will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Settings -> Text(
            text = "Application settings will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Profiles -> Text(
            text = "Configuration profiles will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        SidePanel.Help -> Text(
            text = "Help and documentation will appear here.",
            style = LumoTheme.typography.body2,
            color = LumoTheme.colors.textSecondary,
        )
        else -> {}
    }
}
