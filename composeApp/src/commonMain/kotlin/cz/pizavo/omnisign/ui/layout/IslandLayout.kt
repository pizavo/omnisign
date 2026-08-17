package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedListRefreshFailure
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.port.RenewalActivityProbe
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import cz.pizavo.omnisign.domain.port.SchedulerPort
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import cz.pizavo.omnisign.domain.port.TrustedListRefreshPort
import cz.pizavo.omnisign.domain.repository.CapabilitiesRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.*
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.ui.branding.LocalOrganizationName
import cz.pizavo.omnisign.ui.branding.LocalServerOrganizationName
import cz.pizavo.omnisign.ui.branding.brandedTitle
import cz.pizavo.omnisign.ui.model.*
import cz.pizavo.omnisign.ui.platform.*
import cz.pizavo.omnisign.ui.toast.LocalToastService
import cz.pizavo.omnisign.ui.toast.ToastDuration
import cz.pizavo.omnisign.ui.toast.ToastHost
import cz.pizavo.omnisign.ui.toast.ToastMessage
import cz.pizavo.omnisign.ui.toast.ToastService
import cz.pizavo.omnisign.ui.toast.ToastText
import cz.pizavo.omnisign.ui.viewmodel.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.error.NoDefinitionFoundException
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
 * @param languageTag The active UI language tag (`null` = system default), threaded to the settings
 *   dialog's Language & Region panel. Threaded separately from the persisted config, like the theme.
 * @param dateFormat The active UI date format, threaded to the Language & Region panel.
 * @param onLanguageChange Applies the chosen language tag (`null` = system default) live as a preview
 *   only — it does not persist. The dialog commits it via [onPersistLocale] on save, or reverts it
 *   through this same callback on cancel.
 * @param onFormatChange Applies the chosen date format live as a preview only, like [onLanguageChange].
 * @param onPersistLocale Persists the given language tag and date format. Invoked only when the user
 *   saves the settings dialog, so a previewed language/format reverts on cancel unless it was saved.
 * @param onLogout Sign-out action for the toolbar, forwarded to [IslandToolbar]. `null` (desktop, or
 *   web with auth disabled) hides the sign-out button; the web target passes a non-null action only
 *   when the server has auth enabled.
 * @param modifier Optional [Modifier] applied to the outermost container.
 */
@Composable
fun IslandLayout(
	isDarkTheme: Boolean,
	onToggleTheme: () -> Unit,
	languageTag: String?,
	dateFormat: DateFormat,
	onLanguageChange: (String?) -> Unit,
	onFormatChange: (DateFormat) -> Unit,
	onPersistLocale: (String?, DateFormat) -> Unit,
	onLogout: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val pdfViewModel: PdfViewerViewModel = viewModel { PdfViewerViewModel() }
	val pdfState by pdfViewModel.state.collectAsState()
	val scope = rememberCoroutineScope()

	val capabilitiesViewModel = remember {
		CapabilitiesViewModel(KoinPlatform.getKoinOrNull()?.getOrNull<CapabilitiesRepository>())
	}
	val capabilities by capabilitiesViewModel.capabilities.collectAsState()
	val serverOrganizationName = capabilities.organizationName
	val documentTitle = brandedTitle(LocalOrganizationName.current, serverOrganizationName)
	LaunchedEffect(documentTitle) { updateDocumentTitle(documentTitle) }
	
	val signatureViewModel: SignatureViewModel? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			SignatureViewModel(
				koin.get<ValidateDocumentUseCase>(),
				koin.get<ConfigRepository>(),
				trustedListRefreshPort = koin.getOrNull<TrustedListRefreshPort>(),
			)
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val signatureValidationBlocked by (signatureViewModel?.validationBlocked ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(false)
	}).collectAsState()
	val signatureTrustedListProgress by (signatureViewModel?.trustedListLoadProgress ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress())
	}).collectAsState()
	val signatureState by (signatureViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<SignaturePanelState>(SignaturePanelState.Idle())
	}).collectAsState()
	
	val profileViewModel: ProfileViewModel? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			ProfileViewModel(
				koin.get<ManageProfileUseCase>(),
				koin.get<GetConfigUseCase>(),
				koin.getOrNull<CredentialStore>(),
				koin.getOrNull<TrustStore>(),
			)
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val profileState by (profileViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(ProfileListState())
	}).collectAsState()
	val profileHasEditChanges by (profileViewModel?.hasEditChanges ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(false)
	}).collectAsState()
	
	val settingsViewModel: SettingsViewModel? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			val linux = isLinuxPlatform()
			SettingsViewModel(
				koin.get<GetConfigUseCase>(),
				koin.get<SetGlobalConfigUseCase>(),
				koin.getOrNull<ConfigRepository>(),
				koin.getOrNull<CredentialStore>(),
				koin.getOrNull<SchedulerPort>(),
				autoDetectedExecutablePath = resolveExecutablePath(),
				isLinuxDesktop = linux,
				trustedListRefreshPort = koin.getOrNull<TrustedListRefreshPort>(),
				configArchive = koin.getOrNull<ConfigArchivePort>(),
				trustStore = koin.getOrNull<TrustStore>(),
				renewalRunRecordStore = koin.getOrNull<RenewalRunRecordStore>(),
				renewalActivityProbe = koin.getOrNull<RenewalActivityProbe>(),
			)
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val renewalStatusViewModel: RenewalStatusViewModel? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			RenewalStatusViewModel(
				runRecordStore = koin.getOrNull<RenewalRunRecordStore>(),
				activityProbe = koin.getOrNull<RenewalActivityProbe>(),
			)
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val renewalNeedsAttention by (renewalStatusViewModel?.needsAttention ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(false)
	}).collectAsState()

	val settingsState by (settingsViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(GlobalConfigEditState())
	}).collectAsState()
	val settingsHasChanges by (settingsViewModel?.hasChanges ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(false)
	}).collectAsState()
	val trustedListRefreshing by (settingsViewModel?.trustedListRefreshing ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(false)
	}).collectAsState()
	val trustedListLastRefreshAt by (settingsViewModel?.trustedListLastRefreshAt ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<kotlin.time.Instant?>(null)
	}).collectAsState()
	val trustedListLoadProgress by (settingsViewModel?.trustedListLoadProgress ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress())
	}).collectAsState()
	var showSettingsDialog by remember { mutableStateOf(false) }

	LaunchedEffect(showSettingsDialog) { renewalStatusViewModel?.refresh() }
	var initialSettingsCategory by remember { mutableStateOf<SettingsCategory?>(null) }
	var settingsLanguageBaseline by remember { mutableStateOf(languageTag) }
	var settingsFormatBaseline by remember { mutableStateOf(dateFormat) }
	
	val renewalJobAssigner: RenewalJobAssigner? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			RenewalJobAssigner(
				configRepository = koin.get<ConfigRepository>(),
				schedulerPort = koin.getOrNull<SchedulerPort>(),
				autoDetectedExecutablePath = resolveExecutablePath(),
			)
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	
	val toastService = remember { ToastService() }

	val trustedListRefreshPort = remember { KoinPlatform.getKoinOrNull()?.getOrNull<TrustedListRefreshPort>() }
	val trustedListFailure by (trustedListRefreshPort?.lastFailure ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<TrustedListRefreshFailure?>(null)
	}).collectAsState()
	LaunchedEffect(trustedListFailure) {
		val text = when (val failure = trustedListFailure) {
			null -> return@LaunchedEffect
			is TrustedListRefreshFailure.EuLotl -> ToastText.Resource(Res.string.tlloadingbar_refresh_failed_lotl)
			is TrustedListRefreshFailure.EuLotlAndOthers ->
				ToastText.Resource(Res.string.tlloadingbar_refresh_failed_lotl_and_others)
			is TrustedListRefreshFailure.CustomList ->
				ToastText.Resource(Res.string.tlloadingbar_refresh_failed_custom, listOf(failure.name))
			is TrustedListRefreshFailure.Multiple -> ToastText.Resource(Res.string.tlloadingbar_refresh_failed_several)
		}
		toastService.show(
			ToastMessage(
				text = text,
				actionLabel = ToastText.Resource(Res.string.tlloadingbar_refresh_retry),
				onAction = {
					trustedListRefreshPort?.let { port ->
						scope.launch { withContext(Dispatchers.Default) { runCatching { port.refreshNow() } } }
					}
				},
				duration = ToastDuration.Long,
			),
		)
	}

	val signingViewModel: SigningViewModel? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			SigningViewModel(
				koin.get<SignDocumentUseCase>(),
				koin.get<ListCertificatesUseCase>(),
				koin.get<UnlockTokenUseCase>(),
				koin.get<LoadFileCertificatesUseCase>(),
				koin.get<ConfigRepository>(),
				koin.getOrNull<TokenService>(),
				renewalJobAssigner,
				toastService = toastService,
			)
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val signingState by (signingViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<SigningDialogState>(SigningDialogState.Idle)
	}).collectAsState()
	val signingRenewalOffer by (signingViewModel?.pendingRenewalOffer ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<RenewalJobOfferState?>(null)
	}).collectAsState()
	val signingDiagnosticSnapshot by (signingViewModel?.diagnosticSnapshot ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<cz.pizavo.omnisign.domain.service.Pkcs11DiagnosticSnapshot?>(null)
	}).collectAsState()
	var showSigningDialog by remember { mutableStateOf(false) }
	
	val timestampViewModel: TimestampViewModel? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			TimestampViewModel(
				koin.get<ExtendDocumentUseCase>(),
				koin.get<GetDocumentTimestampInfoUseCase>(),
				koin.get<ConfigRepository>(),
				renewalJobAssigner,
			)
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val timestampState by (timestampViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<TimestampDialogState>(TimestampDialogState.Idle)
	}).collectAsState()
	val timestampRenewalOffer by (timestampViewModel?.pendingRenewalOffer ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<RenewalJobOfferState?>(null)
	}).collectAsState()
	var showTimestampDialog by remember { mutableStateOf(false) }
	
	val trustedCertsViewModel: TrustedCertsViewModel? = remember {
		runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			TrustedCertsViewModel(koin.get<GetConfigUseCase>(), koin.getOrNull<TrustStore>())
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val trustedCertsState by (trustedCertsViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow(TrustedCertsPanelState())
	}).collectAsState()
	
	val tlBuilderViewModel: TlBuilderViewModel? = remember {
		if (isWebPlatform()) null
		else runCatching {
			val koin = KoinPlatform.getKoinOrNull() ?: return@runCatching null
			TlBuilderViewModel(koin.getOrNull<TrustedListCompilerPort>())
		}.recover { if (it is NoDefinitionFoundException || it.cause is NoDefinitionFoundException) null else throw it }.getOrNull()
	}
	val tlBuilderState by (tlBuilderViewModel?.state ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<TlBuilderDialogState>(TlBuilderDialogState.Idle)
	}).collectAsState()
	var showTlBuilderDialog by remember { mutableStateOf(false) }
	
	val passwordController: PasswordDialogController? = remember {
		KoinPlatform.getKoinOrNull()?.getOrNull()
	}
	val passwordRequest by (passwordController?.request ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<PasswordDialogRequest?>(null)
	}).collectAsState()
	
	val documentOpenController = remember { KoinPlatform.getKoinOrNull()?.getOrNull<DocumentOpenController>() }
	val requestedDocument by (documentOpenController?.request ?: remember {
		kotlinx.coroutines.flow.MutableStateFlow<PlatformFile?>(null)
	}).collectAsState()

	suspend fun openDocument(platformFile: PlatformFile) {
		runCatching { loadPdfFromPlatformFile(platformFile) }.fold(
			onSuccess = { document ->
				pdfViewModel.onDocumentLoaded(document)
				signatureViewModel?.onDocumentChanged(document)
				timestampViewModel?.onDocumentChanged(document)
			},
			onFailure = {
				toastService.show(
					ToastMessage(
						text = ToastText.Resource(Res.string.islandlayout_open_document_failed, listOf(platformFile.name)),
						duration = ToastDuration.Long,
					),
				)
			},
		)
	}

	val filePickerLauncher = rememberFilePickerLauncher(
		type = FileKitType.File(extensions = listOf("pdf")),
	) { platformFile: PlatformFile? ->
		if (platformFile != null) {
			scope.launch { openDocument(platformFile) }
		}
	}

	LaunchedEffect(requestedDocument) {
		val platformFile = requestedDocument ?: return@LaunchedEffect
		openDocument(platformFile)
		documentOpenController?.consume()
	}
	
	val leftPanels = remember(capabilities.canValidate) {
			SidePanel.entries.filter {
				it.side == PanelSide.Left && (it != SidePanel.Signature || capabilities.canValidate)
			}
		}
	val rightPanels = remember { SidePanel.entries.filter { it.side == PanelSide.Right } }
	
	var activeLeftPanel by remember { mutableStateOf<SidePanel?>(null) }
	var activeRightPanel by remember { mutableStateOf<SidePanel?>(null) }
	var debugLoggingOn by remember { mutableStateOf(isDebugLoggingEnabled()) }
	
	var leftPanelWidth by remember { mutableStateOf(Dp.Unspecified) }
	var rightPanelWidth by remember { mutableStateOf(Dp.Unspecified) }

	val trustStore = remember { KoinPlatform.getKoinOrNull()?.getOrNull<TrustStore>() }
	val activeProfileName = profileState.activeProfile
	val trustedCertAdder = remember(trustStore, activeProfileName) {
		trustStore?.takeIf { !it.readOnly }?.let { store ->
			TrustedCertificateAdder(activeProfileName = activeProfileName) { der, toActiveProfile, type ->
				withContext(Dispatchers.Default) {
					store.add(
						scope = TrustScope.of(if (toActiveProfile) activeProfileName else null),
						certBytes = der,
						type = type,
						source = "validation report",
					).fold(ifLeft = { it.message }, ifRight = { null })
				}
			}
		}
	}

	CompositionLocalProvider(
		LocalToastService provides toastService,
		LocalTrustedCertificateAdder provides trustedCertAdder,
		LocalServerOrganizationName provides serverOrganizationName,
	) {
		Box(modifier = modifier.fillMaxSize()) {
			Column(modifier = Modifier.fillMaxSize()) {
				IslandToolbar(
					isDarkTheme = isDarkTheme,
					onToggleTheme = onToggleTheme,
					onOpenFile = { filePickerLauncher.launch() },
					onOpenSettings = {
						settingsViewModel?.load()
						trustedCertsViewModel?.refresh()
						settingsLanguageBaseline = languageTag
						settingsFormatBaseline = dateFormat
						showSettingsDialog = true
					},
					onSign = {
						val doc = pdfState.document
						if (doc != null) {
							signingViewModel?.open(doc, allowTimestamping = capabilities.canTimestamp)
							showSigningDialog = true
						}
					},
					onTimestamp = {
						val doc = pdfState.document
						if (doc != null) {
							timestampViewModel?.open(doc)
							showTimestampDialog = true
						}
					},
					canSign = capabilities.canSign,
					canTimestamp = capabilities.canTimestamp,
					fileLoaded = pdfState.document != null,
					onLogout = onLogout,
					renewalNeedsAttention = renewalNeedsAttention,
				)
				
				if (showSettingsDialog) {
					val localeChanged = languageTag != settingsLanguageBaseline || dateFormat != settingsFormatBaseline
					SettingsDialog(
						state = settingsState,
						hasChanges = settingsHasChanges || localeChanged,
						onFieldChange = { transform -> settingsViewModel?.updateState(transform) },
						onSave = {
							if (settingsHasChanges) {
								settingsViewModel?.save(onSuccess = {
									onPersistLocale(languageTag, dateFormat)
									trustedCertsViewModel?.refresh()
									showSettingsDialog = false
									initialSettingsCategory = null
								})
							} else {
								onPersistLocale(languageTag, dateFormat)
								showSettingsDialog = false
								initialSettingsCategory = null
							}
						},
						onDismiss = {
							onLanguageChange(settingsLanguageBaseline)
							onFormatChange(settingsFormatBaseline)
							showSettingsDialog = false
							initialSettingsCategory = null
						},
						onBuildTl = tlBuilderViewModel?.let {
							{
								it.open()
								showTlBuilderDialog = true
							}
						},
						initialCategory = initialSettingsCategory,
						trustedListRefreshing = trustedListRefreshing,
						trustedListLastRefreshAt = trustedListLastRefreshAt,
						trustedListLoadProgress = trustedListLoadProgress,
						onRefreshTrustedLists = { settingsViewModel?.refreshTrustedListsNow() },
						onExportConfig = {
							scope.launch {
								val bytes = settingsViewModel?.buildConfigArchive() ?: return@launch
								exportConfigArchive(bytes, "omnisign-config")
							}
						},
						onImportConfig = {
							scope.launch {
								val bytes = importConfigArchive() ?: return@launch
								settingsViewModel?.importConfiguration(bytes)
							}
						},
						backupEnabled = settingsViewModel?.canBackup == true,
						readOnly = isWebPlatform(),
						onStageTrustedCert = { bytes, type, source ->
							settingsViewModel?.stageGlobalTrustedCert(bytes, type, source)
						},
						languageTag = languageTag,
						dateFormat = dateFormat,
						onLanguageChange = onLanguageChange,
						onFormatChange = onFormatChange,
					)
				}

				if (showSigningDialog) {
					LaunchedEffect(signingState) {
						(signingState as? SigningDialogState.AwaitingSave)?.let { awaiting ->
							val bytes = signingViewModel?.pendingOutputBytes ?: return@let
							val outcome = saveDocument(bytes, awaiting.suggestedName, "pdf", awaiting.inputDirectory)
							signingViewModel.completeSave(outcome)
							if (outcome is SaveOutcome.SavedNameUnknown) {
								toastService.show(
									ToastMessage(
										text = ToastText.Resource(Res.string.save_not_reopened),
										duration = ToastDuration.Long,
									),
								)
							}
						}
					}
					SigningDialog(
						state = signingState,
						canTimestamp = capabilities.canTimestamp,
						onFieldChange = { transform -> signingViewModel?.updateState(transform) },
						onSign = { signingViewModel?.sign() },
						onAbortRevocation = { signingViewModel?.abortAfterRevocationWarning() },
						onAcceptRevocation = { signingViewModel?.acceptRevocationWarning() },
						onUnlockToken = { tokenId -> signingViewModel?.unlockToken(tokenId) },
						onImportPkcs12 = { filePath -> signingViewModel?.loadPkcs12File(filePath) },
						onRescan = { signingViewModel?.rescan() },
						onShowDiagnostic = { signingViewModel?.showDiagnostic() },
						onDismiss = {
							if (signingState is SigningDialogState.Success) {
								val outputFile = (signingState as SigningDialogState.Success).outputFile
								val reloadDoc = signingViewModel?.signedDocument
								scope.launch {
									reloadDocument(outputFile, reloadDoc, pdfViewModel, signatureViewModel, timestampViewModel)
								}
							}
							signingViewModel?.dismiss()
							showSigningDialog = false
						},
					)
				}
				
				signingDiagnosticSnapshot?.let { snapshot ->
					Pkcs11DiagnosticDialog(
						snapshot = snapshot,
						onDismiss = { signingViewModel?.dismissDiagnostic() },
						onOpenPkcs11Settings = if (settingsViewModel != null) {
							{
								signingViewModel?.dismissDiagnostic()
								initialSettingsCategory = SettingsCategory.Pkcs11Libraries
								settingsViewModel.load()
								settingsLanguageBaseline = languageTag
								settingsFormatBaseline = dateFormat
								showSettingsDialog = true
							}
						} else null,
						onOpenDropDirectory = snapshot.dropDirectoryPath?.let { dropDir ->
							{ openInFileExplorer(dropDir) }
						},
					)
				}
				
				if (showTimestampDialog) {
					LaunchedEffect(timestampState) {
						(timestampState as? TimestampDialogState.AwaitingSave)?.let { awaiting ->
							val bytes = timestampViewModel?.pendingOutputBytes ?: return@let
							val outcome = saveDocument(bytes, awaiting.suggestedName, "pdf", awaiting.inputDirectory)
							timestampViewModel.completeSave(outcome)
							if (outcome is SaveOutcome.SavedNameUnknown) {
								toastService.show(
									ToastMessage(
										text = ToastText.Resource(Res.string.save_not_reopened),
										duration = ToastDuration.Long,
									),
								)
							}
						}
					}
					TimestampDialog(
						state = timestampState,
						onFieldChange = { transform -> timestampViewModel?.updateState(transform) },
						onExtend = { timestampViewModel?.extend() },
						onAbortRevocation = { timestampViewModel?.abortAfterRevocationWarning() },
						onAcceptRevocation = { timestampViewModel?.acceptRevocationWarning() },
						onDismiss = {
							if (timestampState is TimestampDialogState.Success) {
								val outputFile = (timestampState as TimestampDialogState.Success).outputFile
								val reloadDoc = timestampViewModel?.extendedDocument
								scope.launch {
									reloadDocument(outputFile, reloadDoc, pdfViewModel, signatureViewModel, timestampViewModel)
								}
							}
							timestampViewModel?.dismiss()
							showTimestampDialog = false
						},
					)
				}
				
				if (signingRenewalOffer != null) {
					RenewalJobOfferDialog(
						state = signingRenewalOffer!!,
						onAssignExisting = { jobName -> signingViewModel?.assignToExistingJob(jobName) },
						onCreateNew = { job -> signingViewModel?.createAndAssignJob(job) },
						onDismiss = { signingViewModel?.dismissRenewalOffer() },
					)
				}
				
				if (timestampRenewalOffer != null) {
					RenewalJobOfferDialog(
						state = timestampRenewalOffer!!,
						onAssignExisting = { jobName -> timestampViewModel?.assignToExistingJob(jobName) },
						onCreateNew = { job -> timestampViewModel?.createAndAssignJob(job) },
						onDismiss = { timestampViewModel?.dismissRenewalOffer() },
					)
				}
				
				if (showTlBuilderDialog) {
					TlBuilderDialog(
						state = tlBuilderState,
						onFieldChange = { transform -> tlBuilderViewModel?.updateState(transform) },
						onAddTsp = { tlBuilderViewModel?.addTsp() },
						onRemoveTsp = { index -> tlBuilderViewModel?.removeTsp(index) },
						onAddService = { tspIndex -> tlBuilderViewModel?.addService(tspIndex) },
						onRemoveService = { tspIndex, svcIndex ->
							tlBuilderViewModel?.removeService(
								tspIndex,
								svcIndex
							)
						},
						onCompile = {
							(tlBuilderState as? TlBuilderDialogState.Editing)?.let { editing ->
								scope.launch {
									val path = chooseSaveDestination(
										suggestedName = editing.name.ifBlank { "trusted-list" },
										extension = "xml",
										initialDirectory = editing.outputDirectory.ifBlank { null },
									)
									if (path != null) tlBuilderViewModel?.compile(path)
								}
							}
						},
						onDismiss = {
							val successState = tlBuilderState as? TlBuilderDialogState.Success
							val tlConfig = successState?.tlConfig
							if (tlConfig != null) {
								settingsViewModel?.updateState { state ->
									state.copy(
										customTrustedLists = state.customTrustedLists.filter { it.name != tlConfig.name } + tlConfig
									)
								}
							}
							tlBuilderViewModel?.dismiss()
							showTlBuilderDialog = false
						},
					)
				}
				
				passwordRequest?.let { req ->
					PasswordDialog(
						title = req.title,
						prompt = req.prompt,
						onConfirm = { passwordController?.complete(it) },
						onCancel = { passwordController?.complete(null) },
					)
				}
				
				BoxWithConstraints(
					modifier = Modifier
						.weight(1f)
						.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
				) {
					val defaultPanelWidth = maxWidth * IslandSidePanelDefaultFraction
					val effectiveLeftWidth = if (leftPanelWidth == Dp.Unspecified) defaultPanelWidth else leftPanelWidth
					val effectiveRightWidth =
						if (rightPanelWidth == Dp.Unspecified) defaultPanelWidth else rightPanelWidth
					
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
							title = activeLeftPanel?.label() ?: "",
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
											availableFormats = signatureViewModel?.availableExportFormats()
												?: emptyList(),
											onFormatSelected = { format ->
												val text =
													signatureViewModel?.exportReport(format) ?: return@ExportReportMenu
												scope.launch {
													exportTextToFile(
														text = text,
														suggestedName = signatureViewModel.suggestedReportFileName(format)
															?: "validation-report",
														extension = format.extension,
													)
												}
											},
										)
									}
									
									TooltipBox(
										tooltip = { Tooltip { Text(text = stringResource(Res.string.islandlayout_refresh_signatures)) } },
										state = rememberTooltipState(),
									) {
										IconButton(
											variant = IconButtonVariant.Ghost,
											enabled = !signatureValidationBlocked,
											onClick = { signatureViewModel?.loadSignatures() },
										) {
											Icon(
												painter = painterResource(Res.drawable.icon_refresh),
												contentDescription = stringResource(Res.string.islandlayout_refresh_signatures),
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
									validationBlocked = signatureValidationBlocked,
									trustedListLoadProgress = signatureTrustedListProgress,
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
						val rightPanelTitle = if (isEditingProfile) stringResource(Res.string.islandlayout_edit_profile)
						else activeRightPanel?.label() ?: ""
						
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
									readOnly = isWebPlatform(),
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
									onBuildTl = tlBuilderViewModel?.let {
										{
											it.open()
											showTlBuilderDialog = true
										}
									},
									onStageTrustedCert = { bytes, type, source ->
										profileViewModel?.stageEditedProfileTrustedCert(bytes, type, source)
									},
								)
								
								SidePanel.TrustedCerts -> TrustedCertsPanel(state = trustedCertsState)

								SidePanel.Help -> HelpPanel(
									debugLoggingEnabled = debugLoggingOn,
									onDebugLoggingChange = { debugLoggingOn = it },
								)

								else -> PanelPlaceholderContent(panel = activeRightPanel)
							}
						}
						
						IslandSideBar(
							panels = rightPanels,
							activePanel = activeRightPanel,
							indicatedPanels = if (debugLoggingOn) setOf(SidePanel.Help) else emptySet(),
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
			
			ToastHost(
				service = toastService,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 60.dp, bottom = 72.dp),
				suppressWhenDialogOpen = true,
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
		SidePanel.Profiles -> Text(
			text = stringResource(Res.string.islandlayout_profiles_placeholder),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
		
		else -> {}
	}
}

/**
 * Reload a document from the disk into the PDF viewer and refresh signature validation.
 *
 * Called after a successful signing or extension operation, so the user sees the
 * updated document immediately.
 *
 * @param filePath Absolute path to the output file to load.
 * @param pdfViewModel Viewer ViewModel to update with the new document.
 * @param signatureViewModel Signature panel ViewModel to re-validate the new document.
 * @param timestampViewModel Timestamp ViewModel to refresh cached timestamp info.
 */
/**
 * Reload a just-produced document into the viewer and the signature / timestamp panels.
 *
 * Prefers reading the written file at [filePath] (desktop); when that yields `null` — the web target,
 * which has no filesystem path — it falls back to [fallbackDoc], the in-memory bytes the ViewModel
 * rebuilt after the save. Does nothing if neither source is available.
 *
 * @param filePath Destination the document was written to (a bare file name on web).
 * @param fallbackDoc In-memory document to use when [filePath] cannot be read; `null` to skip.
 */
private suspend fun reloadDocument(
	filePath: String,
	fallbackDoc: PdfDocumentInfo?,
	pdfViewModel: PdfViewerViewModel,
	signatureViewModel: SignatureViewModel?,
	timestampViewModel: TimestampViewModel?,
) {
	val doc = loadPdfFromPath(filePath) ?: fallbackDoc ?: return
	pdfViewModel.onDocumentLoaded(doc)
	signatureViewModel?.onDocumentChanged(doc)
	timestampViewModel?.onDocumentChanged(doc)
}


