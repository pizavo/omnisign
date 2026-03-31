package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.*
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.ui.model.*
import cz.pizavo.omnisign.ui.platform.exportTextToFile
import cz.pizavo.omnisign.ui.platform.loadPdfFromPlatformFile
import cz.pizavo.omnisign.ui.viewmodel.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_refresh
import org.jetbrains.compose.resources.painterResource
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
	
	val signatureViewModel: SignatureViewModel? = remember {
		val koin = KoinPlatform.getKoinOrNull() ?: return@remember null
		SignatureViewModel(
			koin.get<ValidateDocumentUseCase>(),
			koin.get<ConfigRepository>(),
		)
	}
	val signatureState by (signatureViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<SignaturePanelState>(SignaturePanelState.Idle())
	}).collectAsState()
	
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
	val profileHasEditChanges by (profileViewModel?.hasEditChanges ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(false)
	}).collectAsState()
	
	val settingsViewModel: SettingsViewModel? = remember {
		val koin = KoinPlatform.getKoinOrNull() ?: return@remember null
		SettingsViewModel(
			koin.get<GetConfigUseCase>(),
			koin.get<SetGlobalConfigUseCase>(),
			koin.getOrNull<CredentialStore>(),
		)
	}
	val settingsState by (settingsViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(GlobalConfigEditState())
	}).collectAsState()
	val settingsHasChanges by (settingsViewModel?.hasChanges ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(false)
	}).collectAsState()
	var showSettingsDialog by remember { mutableStateOf(false) }
	
	val signingViewModel: SigningViewModel? = remember {
		val koin = KoinPlatform.getKoinOrNull() ?: return@remember null
		SigningViewModel(
			koin.get<SignDocumentUseCase>(),
			koin.get<ListCertificatesUseCase>(),
			koin.get<ConfigRepository>(),
		)
	}
	val signingState by (signingViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<SigningDialogState>(SigningDialogState.Idle)
	}).collectAsState()
	var showSigningDialog by remember { mutableStateOf(false) }
	
	val timestampViewModel: TimestampViewModel? = remember {
		val koin = KoinPlatform.getKoinOrNull() ?: return@remember null
		TimestampViewModel(
			koin.get<ExtendDocumentUseCase>(),
			koin.get<ConfigRepository>(),
		)
	}
	val timestampState by (timestampViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<TimestampDialogState>(TimestampDialogState.Idle)
	}).collectAsState()
	var showTimestampDialog by remember { mutableStateOf(false) }
	
	val trustedCertsViewModel: TrustedCertsViewModel? = remember {
		val koin = KoinPlatform.getKoinOrNull() ?: return@remember null
		TrustedCertsViewModel(koin.get<GetConfigUseCase>())
	}
	val trustedCertsState by (trustedCertsViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(TrustedCertsPanelState())
	}).collectAsState()
	
	val filePickerLauncher = rememberFilePickerLauncher(
		type = FileKitType.File(extensions = listOf("pdf")),
	) { platformFile: PlatformFile? ->
		if (platformFile != null) {
			scope.launch {
				val document = loadPdfFromPlatformFile(platformFile)
				pdfViewModel.onDocumentLoaded(document)
				signatureViewModel?.onDocumentChanged(document.filePath)
			}
		}
	}
	
	val leftPanels = remember { SidePanel.entries.filter { it.side == PanelSide.Left } }
	val rightPanels = remember { SidePanel.entries.filter { it.side == PanelSide.Right } }
	
	var activeLeftPanel by remember { mutableStateOf<SidePanel?>(null) }
	var activeRightPanel by remember { mutableStateOf<SidePanel?>(null) }
	
	var leftPanelWidth by remember { mutableStateOf(Dp.Unspecified) }
	var rightPanelWidth by remember { mutableStateOf(Dp.Unspecified) }
	
	Column(modifier = modifier.fillMaxSize()) {
		IslandToolbar(
			isDarkTheme = isDarkTheme,
			onToggleTheme = onToggleTheme,
			onOpenFile = { filePickerLauncher.launch() },
			onOpenSettings = {
				settingsViewModel?.load()
				showSettingsDialog = true
			},
			onSign = {
				val filePath = pdfState.document?.filePath
				if (filePath != null) {
					signingViewModel?.open(filePath)
					showSigningDialog = true
				}
			},
			onTimestamp = {
				val filePath = pdfState.document?.filePath
				if (filePath != null) {
					timestampViewModel?.open(filePath)
					showTimestampDialog = true
				}
			},
			fileLoaded = pdfState.document != null,
		)
		
		if (showSettingsDialog) {
			SettingsDialog(
				state = settingsState,
				hasChanges = settingsHasChanges,
				onFieldChange = { transform -> settingsViewModel?.updateState(transform) },
				onSave = { settingsViewModel?.save(onSuccess = { showSettingsDialog = false }) },
				onDismiss = { showSettingsDialog = false },
			)
		}
		
		if (showSigningDialog) {
			SigningDialog(
				state = signingState,
				onFieldChange = { transform -> signingViewModel?.updateState(transform) },
				onSign = { signingViewModel?.sign() },
				onDismiss = {
					if (signingState is SigningDialogState.Success) {
						val outputFile = (signingState as SigningDialogState.Success).outputFile
						scope.launch {
							reloadDocument(outputFile, pdfViewModel, signatureViewModel)
						}
					}
					signingViewModel?.dismiss()
					showSigningDialog = false
				},
			)
		}
		
		if (showTimestampDialog) {
			TimestampDialog(
				state = timestampState,
				onFieldChange = { transform -> timestampViewModel?.updateState(transform) },
				onExtend = { timestampViewModel?.extend() },
				onDismiss = {
					if (timestampState is TimestampDialogState.Success) {
						val outputFile = (timestampState as TimestampDialogState.Success).outputFile
						scope.launch {
							reloadDocument(outputFile, pdfViewModel, signatureViewModel)
						}
					}
					timestampViewModel?.dismiss()
					showTimestampDialog = false
				},
			)
		}
		
		BoxWithConstraints(
			modifier = Modifier
				.weight(1f)
				.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
		) {
			val defaultPanelWidth = maxWidth * IslandSidePanelDefaultFraction
			val effectiveLeftWidth = if (leftPanelWidth == Dp.Unspecified) defaultPanelWidth else leftPanelWidth
			val effectiveRightWidth = if (rightPanelWidth == Dp.Unspecified) defaultPanelWidth else rightPanelWidth
			
			val sideBarCount = (if (leftPanels.isNotEmpty()) 1 else 0) +
					(if (rightPanels.isNotEmpty()) 1 else 0)
			val gapCount = sideBarCount + 1 +
					(if (activeLeftPanel != null) 1 else 0) +
					(if (activeRightPanel != null) 1 else 0)
			val fixedChrome = SideBarWidth * sideBarCount + 4.dp * gapCount
			val panelWidthCap = (maxWidth - SideBarWidth * sideBarCount) / 3
			val safeMinPanelWidth = maxOf(0.dp, minOf(IslandSidePanelMinWidth, panelWidthCap))
			val oppositeRight = if (activeRightPanel != null) effectiveRightWidth else 0.dp
			val oppositeLeft = if (activeLeftPanel != null) effectiveLeftWidth else 0.dp
			val maxLeftPanelWidth = (maxWidth - fixedChrome - oppositeRight)
				.coerceIn(safeMinPanelWidth, maxOf(safeMinPanelWidth, panelWidthCap))
			val maxRightPanelWidth = (maxWidth - fixedChrome - oppositeLeft)
				.coerceIn(safeMinPanelWidth, maxOf(safeMinPanelWidth, panelWidthCap))
			
			Row(
				modifier = Modifier.fillMaxSize(),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				IslandSideBar(
					panels = leftPanels,
					activePanel = activeLeftPanel,
					onPanelToggle = { panel ->
						activeLeftPanel = if (activeLeftPanel == panel) null else panel
					},
					tooltipPlacement = TooltipPlacement.End,
				)
				
				IslandSidePanel(
					visible = activeLeftPanel != null,
					title = activeLeftPanel?.label ?: "",
					onClose = { activeLeftPanel = null },
					panelWidth = effectiveLeftWidth.coerceAtMost(maxLeftPanelWidth),
					defaultWidth = defaultPanelWidth,
					maxPanelWidth = maxLeftPanelWidth,
					onWidthChange = { leftPanelWidth = it },
					fromEnd = false,
				headerActions = if (activeLeftPanel == SidePanel.Signature &&
					pdfState.document != null
				) {
					{
						if (signatureState is SignaturePanelState.Loaded) {
							ExportReportMenu(
								availableFormats = signatureViewModel?.availableExportFormats() ?: emptyList(),
								onFormatSelected = { format ->
									val text = signatureViewModel?.exportReport(format) ?: return@ExportReportMenu
									scope.launch {
										exportTextToFile(
											text = text,
											suggestedName = "validation-report",
											extension = format.extension,
										)
									}
								},
							)
						}

						TooltipBox(
							tooltip = { Tooltip { Text(text = "Refresh signatures") } },
							state = rememberTooltipState(),
						) {
							IconButton(
								variant = IconButtonVariant.Ghost,
								onClick = { signatureViewModel?.loadSignatures() },
							) {
								Icon(
									painter = painterResource(Res.drawable.icon_refresh),
									contentDescription = "Refresh signatures",
									modifier = Modifier.size(20.dp),
								)
							}
						}
					}
				} else null,
					modifier = Modifier.fillMaxHeight(),
				) {
					when (activeLeftPanel) {
						SidePanel.Signature -> SignaturePanel(
							state = signatureState,
							onLoadSignatures = { signatureViewModel?.loadSignatures() },
						)
						
						else -> {}
					}
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
					panelWidth = effectiveRightWidth.coerceAtMost(maxRightPanelWidth),
					defaultWidth = defaultPanelWidth,
					maxPanelWidth = maxRightPanelWidth,
					onWidthChange = { rightPanelWidth = it },
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
							hasEditChanges = profileHasEditChanges,
						)
						
						SidePanel.TrustedCerts -> TrustedCertsPanel(state = trustedCertsState)
						
						else -> PanelPlaceholderContent(panel = activeRightPanel)
					}
				}
				
				IslandSideBar(
					panels = rightPanels,
					activePanel = activeRightPanel,
					onPanelToggle = { panel ->
						activeRightPanel = if (activeRightPanel == panel) null else {
							if (panel == SidePanel.Profiles) profileViewModel?.refresh()
							if (panel == SidePanel.TrustedCerts) trustedCertsViewModel?.refresh()
							panel
						}
					},
					tooltipPlacement = TooltipPlacement.Start,
				)
			}
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

/**
 * Reload a document from disk into the PDF viewer and refresh signature validation.
 *
 * Called after a successful signing or extension operation so the user sees the
 * updated document immediately.
 *
 * @param filePath Absolute path to the output file to load.
 * @param pdfViewModel Viewer ViewModel to update with the new document.
 * @param signatureViewModel Signature panel ViewModel to re-validate the new document.
 */
private suspend fun reloadDocument(
	filePath: String,
	pdfViewModel: PdfViewerViewModel,
	signatureViewModel: SignatureViewModel?,
) {
	val doc = cz.pizavo.omnisign.ui.platform.loadPdfFromPath(filePath) ?: return
	pdfViewModel.onDocumentLoaded(doc)
	signatureViewModel?.onDocumentChanged(filePath)
}


