package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress
import cz.pizavo.omnisign.domain.model.value.DateFormat
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import cz.pizavo.omnisign.ui.model.RegionChoice
import cz.pizavo.omnisign.ui.model.RegionPreset
import cz.pizavo.omnisign.ui.model.SettingsCategory
import cz.pizavo.omnisign.ui.model.TrustedCertAddError
import cz.pizavo.omnisign.ui.model.resolve
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.formattedDateTime
import cz.pizavo.omnisign.ui.platform.openInFileExplorer
import cz.pizavo.omnisign.ui.platform.platformFilePath
import cz.pizavo.omnisign.ui.platform.resolvePkcs11DropDirectory
import io.github.vinceglb.filekit.PlatformFile
import kotlin.time.Instant
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val NavPanelWidth = 220.dp
private val NavItemShape = RoundedCornerShape(6.dp)

/** Max height of the custom-PKCS#11-library list before it scrolls within its own viewport. */
private val Pkcs11ListMaxHeight = 240.dp

/** Library count above which the custom-PKCS#11-library list gets its own scroll area. */
private const val Pkcs11ListScrollThreshold = 5

/**
 * Full-screen modal dialog for editing the global application configuration.
 *
 * Modeled after the IntelliJ Settings dialog: a left navigation sidebar with a
 * category tree and a right content panel showing the selected category's form.
 * The footer contains Cancel and Save buttons.
 *
 * The left sidebar renders [SettingsCategory] groups as expandable headers with
 * indented children. Clicking a group header selects its first child; clicking
 * a leaf selects it directly. The right panel renders the form section matching
 * the currently selected [SettingsCategory].
 *
 * @param state Current [GlobalConfigEditState] from [cz.pizavo.omnisign.ui.viewmodel.SettingsViewModel].
 * @param hasChanges Whether the user has modified any persistable field since the dialog was opened.
 * @param onFieldChange Called with a transform to update a single field in the edit state.
 * @param onSave Called when the user clicks the Save button.
 * @param onDismiss Called when the user clicks Cancel or the close button.
 * @param onBuildTl Called when the user clicks "Build Custom TL" in the trusted lists section,
 *   or `null` when the TL compiler is not available on the current platform.
 * @param initialCategory Optional [SettingsCategory] to preselect when the dialog opens.
 *   The `remember` is keyed on this value, so callers can deep-link to a specific tab by
 *   toggling it before showing the dialog.  Defaults to [SettingsCategory.SigningDefaults].
 * @param onExportConfig Called when the user exports the full configuration archive (Backup section).
 * @param onImportConfig Called when the user imports a full configuration archive (Backup section).
 * @param backupEnabled Whether the Backup export/import controls are enabled (false on platforms
 *   without a file-system backend, e.g. web).
 * @param readOnly When `true`, the entire settings form renders view-only: every input is disabled,
 *   the Save button is hidden, and host-only categories (PKCS#11 libraries, scheduler, renewal jobs)
 *   are removed from the navigation. Used by the web target, whose configuration is server-owned.
 * @param onStageTrustedCert Called with the picked certificate bytes, type, and source path to stage
 *   a global-scope trusted-certificate addition. The certificate is parsed and deduplicated by the
 *   ViewModel before it is staged; the change is committed to the store on Save.
 * @param languageTag The active UI language tag (`null` = system default) for the Language & Region
 *   panel. This is a runtime UI preference threaded separately from [GlobalConfigEditState]; it
 *   applies live as a preview while the dialog is open, and the host persists it on Save and reverts
 *   it on cancel, so it is not part of the staged config the dialog itself writes.
 * @param dateFormat The active UI date format for the Language & Region panel.
 * @param onLanguageChange Called with the chosen language tag (`null` = system default); the host
 *   applies it live as a preview only and persists it when the dialog is saved.
 * @param onFormatChange Called with the chosen date format; applied live as a preview only and
 *   persisted by the host when the dialog is saved.
 */
@Composable
fun SettingsDialog(
	state: GlobalConfigEditState,
	hasChanges: Boolean,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
	onSave: () -> Unit,
	onDismiss: () -> Unit,
	onBuildTl: (() -> Unit)? = null,
	initialCategory: SettingsCategory? = null,
	trustedListRefreshing: Boolean = false,
	trustedListLastRefreshAt: Instant? = null,
	trustedListLoadProgress: TrustedListLoadProgress = TrustedListLoadProgress(),
	onRefreshTrustedLists: () -> Unit = {},
	onExportConfig: () -> Unit = {},
	onImportConfig: () -> Unit = {},
	backupEnabled: Boolean = false,
	readOnly: Boolean = false,
	onStageTrustedCert: (ByteArray, TrustedCertificateType, String) -> Unit = { _, _, _ -> },
	languageTag: String? = null,
	dateFormat: DateFormat = DateFormat.SYSTEM,
	onLanguageChange: (String?) -> Unit = {},
	onFormatChange: (DateFormat) -> Unit = {},
) {
	var selectedCategory by remember(initialCategory) {
		mutableStateOf(initialCategory ?: SettingsCategory.SigningDefaults)
	}

	val visibleGroups = remember(state.showNativeTitleBarOption, readOnly) {
		SettingsCategory.groups.filter { group ->
			when (group) {
				SettingsCategory.Appearance -> state.showNativeTitleBarOption
				SettingsCategory.Archiving, SettingsCategory.Tokens -> !readOnly
				else -> true
			}
		}
	}
	
	Dialog(
		onDismissRequest = onDismiss,
		modifier = Modifier
			.widthIn(min = 700.dp, max = 920.dp)
			.heightIn(min = 500.dp, max = 720.dp),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			SettingsHeader(onClose = onDismiss)
			
			HorizontalDivider()
			
			Row(modifier = Modifier.weight(1f)) {
				SettingsNavPanel(
					selected = selectedCategory,
					onSelect = { selectedCategory = it },
					visibleGroups = visibleGroups,
				)
				
				VerticalDivider()
				
				CompositionLocalProvider(LocalReadOnly provides readOnly) {
					SettingsContentPanel(
						category = selectedCategory,
						state = state,
						onFieldChange = onFieldChange,
						onBuildTl = onBuildTl,
						trustedListRefreshing = trustedListRefreshing,
						trustedListLastRefreshAt = trustedListLastRefreshAt,
						trustedListLoadProgress = trustedListLoadProgress,
						onRefreshTrustedLists = onRefreshTrustedLists,
						onExportConfig = onExportConfig,
						onImportConfig = onImportConfig,
						backupEnabled = backupEnabled,
						onStageTrustedCert = onStageTrustedCert,
						languageTag = languageTag,
						dateFormat = dateFormat,
						onLanguageChange = onLanguageChange,
						onFormatChange = onFormatChange,
					)
				}
			}

			HorizontalDivider()
			
			SettingsFooter(saving = state.saving, hasChanges = hasChanges, onCancel = onDismiss, onSave = onSave, readOnly = readOnly)
		}
	}
}

/**
 * Header row with the "Settings" title and close button.
 *
 * @param onClose Callback invoked when the close button is clicked.
 */
@Composable
private fun SettingsHeader(onClose: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = stringResource(Res.string.label_settings), style = LumoTheme.typography.h3)
		IconButton(
			variant = IconButtonVariant.Ghost,
			onClick = onClose,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = stringResource(Res.string.settings_close_description),
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

/**
 * Left navigation sidebar displaying the settings category tree.
 *
 * Groups are rendered as collapsible headers with a chevron indicator; their
 * children are indented below and slide in/out with an animated transition.
 * Clicking a collapsed group expands it and selects its first child. Clicking
 * an already-expanded group collapses it. A group whose child is selected is
 * always kept expanded.
 *
 * @param selected The currently active [SettingsCategory].
 * @param onSelect Callback invoked when the user clicks a category.
 * @param visibleGroups Top-level groups to display. Categories whose group is not in this
 *   list are hidden from the navigation sidebar (e.g. [SettingsCategory.Appearance] on
 *   non-Linux platforms).
 */
@Composable
private fun SettingsNavPanel(
	selected: SettingsCategory,
	onSelect: (SettingsCategory) -> Unit,
	visibleGroups: List<SettingsCategory> = SettingsCategory.groups,
) {
	var expandedGroups by remember {
		mutableStateOf(setOf(visibleGroups.first()))
	}
	
	if (selected.parent != null && selected.parent !in expandedGroups) {
		expandedGroups = expandedGroups + selected.parent
	}
	
	VerticalScrollableColumn(
		modifier = Modifier
			.width(NavPanelWidth)
			.fillMaxHeight(),
		contentPadding = PaddingValues(8.dp),
	) {
		visibleGroups.forEach { group ->
			val isExpanded = group in expandedGroups
			val isActive = selected == group || selected.parent == group
			
			NavGroupItem(
				category = group,
				isActive = isActive,
				isExpanded = isExpanded,
				onClick = {
					if (isExpanded && !isActive) {
						expandedGroups = expandedGroups - group
					} else if (!isExpanded) {
						expandedGroups = expandedGroups + group
						val firstChild = group.children.firstOrNull()
						if (firstChild != null) onSelect(firstChild)
					} else {
						expandedGroups = expandedGroups - group
					}
				},
			)
			
			AnimatedVisibility(
				visible = isExpanded,
				enter = expandVertically(),
				exit = shrinkVertically(),
			) {
				Column {
					group.children.forEach { child ->
						NavLeafItem(
							category = child,
							isSelected = selected == child,
							onClick = { onSelect(child) },
						)
					}
				}
			}
			
			Spacer(modifier = Modifier.height(4.dp))
		}
	}
}

/**
 * Collapsible group header item in the navigation sidebar.
 *
 * Renders a chevron indicator that rotates between pointing right (collapsed)
 * and pointing down (expanded), followed by the group label.
 *
 * @param category The group [SettingsCategory].
 * @param isActive Whether this group or one of its children is selected.
 * @param isExpanded Whether the group's children are currently visible.
 * @param onClick Callback invoked on click.
 */
@Composable
private fun NavGroupItem(
	category: SettingsCategory,
	isActive: Boolean,
	isExpanded: Boolean,
	onClick: () -> Unit,
) {
	val textColor = if (isActive) LumoTheme.colors.text else LumoTheme.colors.textSecondary
	val chevronRotation = if (isExpanded) 0f else -90f
	
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(NavItemShape)
			.clickable(onClick = onClick)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Icon(
			painter = painterResource(Res.drawable.icon_chevron_down),
			contentDescription = if (isExpanded) stringResource(Res.string.action_collapse) else stringResource(Res.string.action_expand),
			modifier = Modifier
				.size(14.dp)
				.graphicsLayer(rotationZ = chevronRotation),
			tint = textColor,
		)
		Text(
			text = category.label(),
			style = LumoTheme.typography.label1,
			color = textColor,
		)
	}
}

/**
 * Indented leaf item in the navigation sidebar.
 *
 * When [isSelected] is true the item receives a highlighted background matching
 * the primary color at reduced opacity, mimicking the IntelliJ selection style.
 *
 * @param category The leaf [SettingsCategory].
 * @param isSelected Whether this category is currently active.
 * @param onClick Callback invoked on click.
 */
@Composable
private fun NavLeafItem(
	category: SettingsCategory,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	val backgroundColor = if (isSelected) {
		LumoTheme.colors.primary.copy(alpha = 0.15f)
	} else {
		LumoTheme.colors.surface
	}
	val textColor = if (isSelected) LumoTheme.colors.primary else LumoTheme.colors.text
	
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 12.dp)
			.clip(NavItemShape)
			.background(backgroundColor)
			.clickable(onClick = onClick)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = category.label(),
			style = LumoTheme.typography.body2,
			color = textColor,
		)
	}
}

/**
 * Right content panel that renders the form section for the selected [category].
 *
 * Displays the category title and description at the top, followed by the
 * section-specific form fields in a scrollable column. An error banner is shown
 * above the title when [GlobalConfigEditState.error] is non-null.
 *
 * @param category The currently selected [SettingsCategory].
 * @param state Current global config edit state.
 * @param onFieldChange Called with a transform to update a single field.
 * @param onBuildTl Called when the user clicks "Build Custom TL", or `null` when unavailable.
 * @param languageTag The active UI language tag (`null` = system default) for the Language & Region panel.
 * @param dateFormat The active UI date format for the Language & Region panel.
 * @param onLanguageChange Called with the chosen language tag (`null` = system default).
 * @param onFormatChange Called with the chosen date format.
 */
@Composable
private fun SettingsContentPanel(
	category: SettingsCategory,
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
	onBuildTl: (() -> Unit)? = null,
	trustedListRefreshing: Boolean = false,
	trustedListLastRefreshAt: Instant? = null,
	trustedListLoadProgress: TrustedListLoadProgress = TrustedListLoadProgress(),
	onRefreshTrustedLists: () -> Unit = {},
	onExportConfig: () -> Unit = {},
	onImportConfig: () -> Unit = {},
	backupEnabled: Boolean = false,
	onStageTrustedCert: (ByteArray, TrustedCertificateType, String) -> Unit = { _, _, _ -> },
	languageTag: String? = null,
	dateFormat: DateFormat = DateFormat.SYSTEM,
	onLanguageChange: (String?) -> Unit = {},
	onFormatChange: (DateFormat) -> Unit = {},
) {
	VerticalScrollableColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(24.dp),
	) {
		val errorText = state.error?.resolve()
		if (errorText != null) {
			SelectableContent {
				Text(
					text = errorText,
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.error,
				)
			}
			Spacer(modifier = Modifier.height(8.dp))
		}
		
		Text(text = category.label(), style = LumoTheme.typography.h3)
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = category.description(),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
		
		Spacer(modifier = Modifier.height(16.dp))
		HorizontalDivider()
		Spacer(modifier = Modifier.height(16.dp))
		
		when (category) {
			SettingsCategory.Signing,
			SettingsCategory.SigningDefaults -> SigningDefaultsSection(state = state, onFieldChange = onFieldChange)
			
			SettingsCategory.DisabledAlgorithms -> DisabledAlgorithmsSection(
				state = state,
				onFieldChange = onFieldChange
			)
			
			SettingsCategory.Services,
			SettingsCategory.TimestampServer -> TimestampSection(state = state, onFieldChange = onFieldChange)
			
			SettingsCategory.OcspCrl -> OcspCrlSection(state = state, onFieldChange = onFieldChange)
			SettingsCategory.Validation,
			SettingsCategory.ValidationPolicy -> ValidationPolicySection(
				state = state,
				onFieldChange = onFieldChange,
				trustedListRefreshing = trustedListRefreshing,
				trustedListLastRefreshAt = trustedListLastRefreshAt,
				trustedListLoadProgress = trustedListLoadProgress,
				onRefreshTrustedLists = onRefreshTrustedLists,
			)
			
			SettingsCategory.AlgorithmConstraints -> AlgorithmConstraintsSection(
				state = state,
				onFieldChange = onFieldChange
			)
			
			SettingsCategory.TrustedCertificates -> TrustedCertificatesSettingsSection(
				state = state,
				onFieldChange = onFieldChange,
				onStageTrustedCert = onStageTrustedCert,
			)

			SettingsCategory.CustomTrustedLists -> CustomTrustedListsSection(
				trustedLists = state.customTrustedLists,
				onAdd = { tl ->
					onFieldChange {
						it.copy(
							customTrustedLists = it.customTrustedLists.filter { existing -> existing.name != tl.name } + tl
						)
					}
				},
				onRemove = { index ->
					onFieldChange {
						it.copy(
							customTrustedLists = it.customTrustedLists.toMutableList().apply { removeAt(index) }
						)
					}
				},
				addError = state.tlAddError,
				onClearError = { onFieldChange { it.copy(tlAddError = null) } },
				onError = { message -> onFieldChange { it.copy(tlAddError = message) } },
				onBuild = onBuildTl,
			)
			
			SettingsCategory.Tokens,
			SettingsCategory.Pkcs11Libraries -> Pkcs11Section(state = state, onFieldChange = onFieldChange)
			
			SettingsCategory.Archiving,
			SettingsCategory.RenewalJobs -> RenewalJobsSection(state = state, onFieldChange = onFieldChange)
			
			SettingsCategory.Scheduler -> SchedulerSection(state = state, onFieldChange = onFieldChange)
			
			SettingsCategory.Backup,
			SettingsCategory.ConfigBackup -> ConfigBackupSection(
				enabled = backupEnabled,
				onExport = onExportConfig,
				onImport = onImportConfig,
			)

			SettingsCategory.Appearance,
			SettingsCategory.WindowTitleBar -> AppearanceWindowSection(state = state, onFieldChange = onFieldChange)

			SettingsCategory.LanguageRegion,
			SettingsCategory.LanguageRegionSettings -> LanguageRegionSection(
				languageTag = languageTag,
				dateFormat = dateFormat,
				onLanguageChange = onLanguageChange,
				onFormatChange = onFormatChange,
			)
		}
	}
}

/**
 * Footer row with Cancel and Save buttons.
 *
 * @param saving Whether a save operation is currently in progress.
 * @param hasChanges Whether any persistable field differs from the originally loaded state.
 * @param onCancel Callback invoked when Cancel is clicked.
 * @param onSave Callback invoked when Save is clicked.
 * @param readOnly When `true`, the Save button is hidden so the footer offers only Cancel.
 */
@Composable
private fun SettingsFooter(
	saving: Boolean,
	hasChanges: Boolean,
	onCancel: () -> Unit,
	onSave: () -> Unit,
	readOnly: Boolean = false,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
	) {
		Button(
			text = stringResource(Res.string.action_cancel),
			variant = ButtonVariant.Ghost,
			onClick = onCancel,
		)
		if (!readOnly) {
			Button(
				text = stringResource(Res.string.action_save),
				variant = ButtonVariant.Primary,
				enabled = hasChanges && !saving,
				loading = saving,
				onClick = onSave,
			)
		}
	}
}

/**
 * Configuration backup section: export the full configuration to a ZIP archive, or import one to
 * replace it. Import is a destructive, whole-configuration replace, so it is confirmed inline.
 *
 * @param enabled Whether export/import is available (false on platforms without a file-system
 *   backend, e.g. web).
 * @param onExport Called when the user starts an export.
 * @param onImport Called when the user confirms an import.
 */
@Composable
private fun ConfigBackupSection(
	enabled: Boolean,
	onExport: () -> Unit,
	onImport: () -> Unit,
) {
	var confirmingImport by remember { mutableStateOf(false) }

	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		TooltipBox(
			tooltip = { Tooltip { Text(text = stringResource(Res.string.settings_backup_export_tooltip)) } },
			state = rememberTooltipState(),
		) {
			IconButton(
				variant = IconButtonVariant.PrimaryOutlined,
				enabled = enabled,
				onClick = onExport,
			) {
				Icon(
					painter = painterResource(Res.drawable.icon_download),
					contentDescription = stringResource(Res.string.settings_backup_export_description),
					modifier = Modifier.size(20.dp),
				)
			}
		}
		TooltipBox(
			tooltip = { Tooltip { Text(text = stringResource(Res.string.settings_backup_import_tooltip)) } },
			state = rememberTooltipState(),
		) {
			IconButton(
				variant = IconButtonVariant.PrimaryOutlined,
				enabled = enabled,
				onClick = { confirmingImport = true },
			) {
				Icon(
					painter = painterResource(Res.drawable.icon_upload),
					contentDescription = stringResource(Res.string.settings_backup_import_description),
					modifier = Modifier.size(20.dp),
				)
			}
		}
	}

	if (confirmingImport) {
		Spacer(modifier = Modifier.height(16.dp))
		Text(
			text = stringResource(Res.string.settings_backup_import_warning),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.error,
		)
		Spacer(modifier = Modifier.height(8.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			TooltipBox(
				tooltip = { Tooltip { Text(text = stringResource(Res.string.action_cancel)) } },
				state = rememberTooltipState(),
			) {
				IconButton(
					variant = IconButtonVariant.Ghost,
					onClick = { confirmingImport = false },
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_x),
						contentDescription = stringResource(Res.string.settings_backup_cancel_import_description),
						modifier = Modifier.size(20.dp),
					)
				}
			}
			TooltipBox(
				tooltip = { Tooltip { Text(text = stringResource(Res.string.settings_backup_confirm_import_tooltip)) } },
				state = rememberTooltipState(),
			) {
				IconButton(
					variant = IconButtonVariant.Destructive,
					onClick = {
						confirmingImport = false
						onImport()
					},
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_check),
						contentDescription = stringResource(Res.string.settings_backup_confirm_import_description),
						modifier = Modifier.size(20.dp),
					)
				}
			}
		}
	}
}

/**
 * Signing defaults section: hash algorithm, encryption algorithm, and timestamp level checkboxes.
 */
@Composable
private fun SigningDefaultsSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	DropdownSelector(
		selected = state.defaultHashAlgorithm,
		options = HashAlgorithm.entries.toList(),
		onSelect = { value ->
			onFieldChange { it.copy(defaultHashAlgorithm = value ?: HashAlgorithm.SHA256) }
		},
		label = { Text(text = stringResource(Res.string.label_hash_algorithm)) },
		showNullOption = false,
		disabledOptions = state.disabledHashAlgorithms,
		itemLabel = { it.name },
		modifier = Modifier.fillMaxWidth(),
	)
	
	Spacer(modifier = Modifier.height(8.dp))
	
	DropdownSelector(
		selected = state.defaultEncryptionAlgorithm,
		options = EncryptionAlgorithm.entries.toList(),
		onSelect = { value -> onFieldChange { it.copy(defaultEncryptionAlgorithm = value) } },
		label = { Text(text = stringResource(Res.string.label_encryption_algorithm)) },
		nullLabel = stringResource(Res.string.settings_signing_encryption_auto_detect),
		disabledOptions = state.disabledEncryptionAlgorithms,
		itemLabel = { it.name },
		modifier = Modifier.fillMaxWidth(),
	)
	
	Spacer(modifier = Modifier.height(12.dp))
	
	Text(text = stringResource(Res.string.settings_signing_timestamp_level_label), style = LumoTheme.typography.label1)
	Spacer(modifier = Modifier.height(4.dp))
	
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Checkbox(
			checked = state.addSignatureTimestamp,
			onCheckedChange = { checked ->
				onFieldChange { it.copy(addSignatureTimestamp = checked) }
			},
			enabled = !state.addArchivalTimestamp,
		)
		Text(text = stringResource(Res.string.label_signature_timestamp), style = LumoTheme.typography.body2)
		InfoTooltip(text = stringResource(Res.string.label_produces_b_lt))
	}
	
	Spacer(modifier = Modifier.height(4.dp))
	
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Checkbox(
			checked = state.addArchivalTimestamp,
			onCheckedChange = { checked ->
				onFieldChange {
					if (checked) it.copy(addArchivalTimestamp = true, addSignatureTimestamp = true)
					else it.copy(addArchivalTimestamp = false)
				}
			},
		)
		Text(text = stringResource(Res.string.label_archival_timestamp), style = LumoTheme.typography.body2)
		InfoTooltip(text = stringResource(Res.string.label_produces_b_lta))
	}
}

/**
 * Chip-based toggles for disabling specific hash and encryption algorithms globally.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisabledAlgorithmsSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	Text(text = stringResource(Res.string.label_disabled_hash_algorithms), style = LumoTheme.typography.label1)
	Spacer(modifier = Modifier.height(4.dp))
	
	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		HashAlgorithm.entries.forEach { algo ->
			val disabled = algo in state.disabledHashAlgorithms
			Chip(
				label = { Text(text = algo.name, style = LumoTheme.typography.body2) },
				selected = disabled,
				onClick = {
					onFieldChange {
						val updated = if (disabled) {
							it.disabledHashAlgorithms - algo
						} else {
							it.disabledHashAlgorithms + algo
						}
						it.copy(disabledHashAlgorithms = updated)
					}
				},
			)
		}
	}
	
	Spacer(modifier = Modifier.height(16.dp))
	
	Text(text = stringResource(Res.string.label_disabled_encryption_algorithms), style = LumoTheme.typography.label1)
	Spacer(modifier = Modifier.height(4.dp))
	
	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		EncryptionAlgorithm.entries.forEach { algo ->
			val disabled = algo in state.disabledEncryptionAlgorithms
			Chip(
				label = { Text(text = algo.name, style = LumoTheme.typography.body2) },
				selected = disabled,
				onClick = {
					onFieldChange {
						val updated = if (disabled) {
							it.disabledEncryptionAlgorithms - algo
						} else {
							it.disabledEncryptionAlgorithms + algo
						}
						it.copy(disabledEncryptionAlgorithms = updated)
					}
				},
			)
		}
	}
}

/**
 * Timestamp server toggle switch and configuration fields.
 */
@Composable
private fun TimestampSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = stringResource(Res.string.settings_tsp_enable_label), style = LumoTheme.typography.label1)
		Switch(
			checked = state.timestampEnabled,
			onCheckedChange = { value -> onFieldChange { it.copy(timestampEnabled = value) } },
		)
	}
	
	if (state.timestampEnabled) {
		Spacer(modifier = Modifier.height(12.dp))
		
		UnderlinedTextField(
			value = state.timestampUrl,
			onValueChange = { value -> onFieldChange { it.copy(timestampUrl = value) } },
			label = { Text(text = stringResource(Res.string.label_url)) },
			placeholder = { Text(text = "https://tsa.example.com/tsr") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		
		Spacer(modifier = Modifier.height(8.dp))
		
		UnderlinedTextField(
			value = state.timestampUsername,
			onValueChange = { value -> onFieldChange { it.copy(timestampUsername = value) } },
			label = { Text(text = stringResource(Res.string.label_username)) },
			placeholder = { Text(text = stringResource(Res.string.label_optional)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)

		Spacer(modifier = Modifier.height(8.dp))

		SettingsPasswordField(
			value = state.timestampPassword,
			onValueChange = { value -> onFieldChange { it.copy(timestampPassword = value) } },
			hasStoredPassword = state.hasStoredPassword,
		)
		
		Spacer(modifier = Modifier.height(8.dp))
		
		UnderlinedTextField(
			value = state.timestampTimeout,
			onValueChange = { value ->
				if (value.all { c -> c.isDigit() }) {
					onFieldChange { it.copy(timestampTimeout = value) }
				}
			},
			label = { Text(text = stringResource(Res.string.label_timeout_ms)) },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * OCSP and CRL timeout fields.
 */
@Composable
private fun OcspCrlSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	UnderlinedTextField(
		value = state.ocspTimeout,
		onValueChange = { value ->
			if (value.all { c -> c.isDigit() }) {
				onFieldChange { it.copy(ocspTimeout = value) }
			}
		},
		label = { Text(text = stringResource(Res.string.settings_ocsp_timeout_label)) },
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		modifier = Modifier.fillMaxWidth(),
	)
	
	Spacer(modifier = Modifier.height(8.dp))
	
	UnderlinedTextField(
		value = state.crlTimeout,
		onValueChange = { value ->
			if (value.all { c -> c.isDigit() }) {
				onFieldChange { it.copy(crlTimeout = value) }
			}
		},
		label = { Text(text = stringResource(Res.string.settings_crl_timeout_label)) },
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		modifier = Modifier.fillMaxWidth(),
	)
}

/**
 * Validation policy, revocation checking, and EU LOTL settings.
 */
@Composable
private fun ValidationPolicySection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
	trustedListRefreshing: Boolean = false,
	trustedListLastRefreshAt: Instant? = null,
	trustedListLoadProgress: TrustedListLoadProgress = TrustedListLoadProgress(),
	onRefreshTrustedLists: () -> Unit = {},
) {
	DropdownSelector(
		selected = state.validationPolicyType,
		options = ValidationPolicyType.entries.toList(),
		onSelect = { value ->
			onFieldChange { it.copy(validationPolicyType = value ?: ValidationPolicyType.DEFAULT_ETSI) }
		},
		label = { Text(text = stringResource(Res.string.settings_validation_policy_label)) },
		showNullOption = false,
		itemLabel = { it.name.replace("_", " ") },
		modifier = Modifier.fillMaxWidth(),
	)
	
	if (state.validationPolicyType == ValidationPolicyType.CUSTOM_FILE) {
		Spacer(modifier = Modifier.height(8.dp))
		
		val policyFilePicker = rememberFilePickerLauncher(
			type = FileKitType.File(extensions = listOf("xml")),
		) { file: PlatformFile? ->
			val path = file?.let { platformFilePath(it) }
			if (path != null) {
				onFieldChange { it.copy(customPolicyPath = path) }
			}
		}
		
		UnderlinedTextField(
			value = state.customPolicyPath,
			onValueChange = { value -> onFieldChange { it.copy(customPolicyPath = value) } },
			label = { Text(text = stringResource(Res.string.settings_validation_custom_policy_path_label)) },
			placeholder = { Text(text = "/path/to/policy.xml") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			trailingIcon = {
				TooltipBox(
					tooltip = { Tooltip { Text(text = stringResource(Res.string.action_browse)) } },
					state = rememberTooltipState()
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { policyFilePicker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_folder),
							contentDescription = stringResource(Res.string.settings_browse_policy_file_description),
							modifier = Modifier.size(18.dp),
						)
					}
				}
			},
		)
	}
	
	Spacer(modifier = Modifier.height(16.dp))
	
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = stringResource(Res.string.settings_validation_check_revocation), style = LumoTheme.typography.label1)
		Switch(
			checked = state.checkRevocation,
			onCheckedChange = { value -> onFieldChange { it.copy(checkRevocation = value) } },
		)
	}
	
	Spacer(modifier = Modifier.height(8.dp))
	
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = stringResource(Res.string.settings_validation_use_eu_lotl), style = LumoTheme.typography.label1)
		Switch(
			checked = state.useEuLotl,
			onCheckedChange = { value -> onFieldChange { it.copy(useEuLotl = value) } },
		)
	}
	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(text = stringResource(Res.string.label_alert_not_eu_lotl), style = LumoTheme.typography.label1)
			InfoTooltip(
				text = stringResource(Res.string.settings_validation_alert_not_eu_lotl_tooltip),
			)
		}
		Switch(
			checked = state.alertIfNotEuLotl,
			onCheckedChange = { value -> onFieldChange { it.copy(alertIfNotEuLotl = value) } },
			enabled = state.useEuLotl,
		)
	}
	Spacer(modifier = Modifier.height(8.dp))

	UnderlinedTextField(
		value = state.trustedListRefreshInterval,
		onValueChange = { value ->
			if (value.all { c -> c.isDigit() }) {
				onFieldChange { it.copy(trustedListRefreshInterval = value) }
			}
		},
		label = { Text(text = stringResource(Res.string.settings_validation_tl_refresh_interval_label)) },
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		modifier = Modifier.fillMaxWidth(),
	)
	Spacer(modifier = Modifier.height(12.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(text = stringResource(Res.string.settings_validation_trusted_lists_label), style = LumoTheme.typography.label1)
			Text(
				text = trustedListLastRefreshAt
					?.let { stringResource(Res.string.settings_validation_tl_last_refreshed, it.formattedDateTime()) }
					?: stringResource(Res.string.settings_validation_tl_last_refreshed_never),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
		}
		TooltipBox(
			tooltip = { Tooltip { Text(text = stringResource(Res.string.settings_validation_refresh_tl_tooltip)) } },
			state = rememberTooltipState(),
		) {
			IconButton(
				variant = IconButtonVariant.Ghost,
				enabled = !trustedListRefreshing,
				loading = trustedListRefreshing,
				onClick = onRefreshTrustedLists,
			) {
				Icon(
					painter = painterResource(Res.drawable.icon_refresh),
					contentDescription = stringResource(Res.string.settings_validation_refresh_tl_tooltip),
					modifier = Modifier.size(20.dp),
				)
			}
		}
	}

	if (trustedListRefreshing) {
		Spacer(modifier = Modifier.height(8.dp))
		TrustedListLoadingBar(progress = trustedListLoadProgress)
	}
}

/**
 * Algorithm constraint level selectors for validation.
 *
 * `IGNORE` is intentionally not offered here — it skips the expiration check silently, producing no
 * message — so the lightest selectable level is `INFORM`, which still surfaces an informational note
 * in the validation report. `IGNORE` remains valid in config files (CLI / server).
 */
@Composable
private fun AlgorithmConstraintsSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	DropdownSelector(
		selected = state.algoExpirationLevel,
		options = AlgorithmConstraintLevel.entries.filter { it != AlgorithmConstraintLevel.IGNORE },
		onSelect = { value ->
			onFieldChange { it.copy(algoExpirationLevel = value ?: AlgorithmConstraintLevel.FAIL) }
		},
		label = { Text(text = stringResource(Res.string.settings_algo_expiration_level_label)) },
		showNullOption = false,
		itemLabel = { it.name },
		modifier = Modifier.fillMaxWidth(),
	)
	
	Spacer(modifier = Modifier.height(8.dp))
	
	DropdownSelector(
		selected = state.algoExpirationLevelAfterUpdate,
		options = AlgorithmConstraintLevel.entries.filter { it != AlgorithmConstraintLevel.IGNORE },
		onSelect = { value ->
			onFieldChange {
				it.copy(algoExpirationLevelAfterUpdate = value ?: AlgorithmConstraintLevel.WARN)
			}
		},
		label = { Text(text = stringResource(Res.string.settings_algo_expiration_level_after_label)) },
		showNullOption = false,
		itemLabel = { it.name },
		modifier = Modifier.fillMaxWidth(),
	)
}

/**
 * Global-scope trusted certificates section, backed by the app-managed trust store.
 *
 * Lists the global directly-trusted certificates and an inline add form. Additions and removals are
 * staged into the [GlobalConfigEditState] and committed to the trust store only when the dialog is
 * saved, so Cancel discards them — consistent with the rest of the settings form. On platforms
 * without a trust store backend (web) an explanatory message is shown instead of the controls.
 *
 * @param state Current global config edit state holding the certificate baseline and staged changes.
 * @param onFieldChange Called with a transform to update the staged certificate fields.
 * @param onStageTrustedCert Called with the picked certificate bytes, type, and source to parse and
 *   stage a global addition (dedup-checked by the ViewModel).
 */
@Composable
private fun TrustedCertificatesSettingsSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
	onStageTrustedCert: (ByteArray, TrustedCertificateType, String) -> Unit,
) {
	if (!state.trustedCertsAvailable) {
		Text(
			text = stringResource(Res.string.msg_trusted_certs_unavailable),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
		return
	}

	TrustedCertificatesSection(
		certificates = state.trustedCertificates,
		pendingAdditions = state.pendingTrustedCertAdds,
		pendingRemovals = state.pendingTrustedCertRemovals,
		onStageAddition = onStageTrustedCert,
		onStageRemoval = { fingerprint ->
			onFieldChange { it.copy(pendingTrustedCertRemovals = it.pendingTrustedCertRemovals + fingerprint) }
		},
		onUnstageRemoval = { fingerprint ->
			onFieldChange { it.copy(pendingTrustedCertRemovals = it.pendingTrustedCertRemovals - fingerprint) }
		},
		onUnstageAddition = { index ->
			onFieldChange {
				it.copy(pendingTrustedCertAdds = it.pendingTrustedCertAdds.filterIndexed { i, _ -> i != index })
			}
		},
		addError = state.trustedCertAddError?.resolve(),
		onClearError = { onFieldChange { it.copy(trustedCertAddError = null) } },
		onError = { message -> onFieldChange { it.copy(trustedCertAddError = TrustedCertAddError.Domain(LocalizableText.Literal(message))) } },
	)
}

/**
 * PKCS#11 middleware libraries section with add/remove support.
 *
 * Renders a drop-directory hint at the top (when the platform provides one),
 * the existing custom entries with remove buttons, and an inline add-row at
 * the bottom.  The drop-directory hint surfaces the platform-appropriate
 * folder where a downloaded library file can be placed for automatic
 * discovery, with an inline clickable link that reveals the folder in the
 * host OS file manager so users don't have to copy-paste the path.
 */
@Composable
private fun Pkcs11Section(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	UnderlinedTextField(
		value = state.pkcs11ProbeTimeout,
		onValueChange = { value ->
			if (value.all { c -> c.isDigit() }) {
				onFieldChange { it.copy(pkcs11ProbeTimeout = value) }
			}
		},
		label = { Text(text = stringResource(Res.string.settings_pkcs11_probe_timeout_label)) },
		singleLine = true,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
	)
	Spacer(modifier = Modifier.height(16.dp))

	val dropDir = remember { resolvePkcs11DropDirectory() }
	if (dropDir != null) {
		Pkcs11DropDirectoryHint(path = dropDir, onOpen = { openInFileExplorer(dropDir) })
		Spacer(modifier = Modifier.height(12.dp))
	}

	Pkcs11AddRow(
		onAdd = { name, path, protectedPinPad ->
			onFieldChange {
				it.copy(
					customPkcs11Libraries = it.customPkcs11Libraries + CustomPkcs11Library(
						name = name,
						path = path,
						protectedAuthenticationPath = protectedPinPad,
					)
				)
			}
		},
	)
	Spacer(modifier = Modifier.height(12.dp))

	if (state.customPkcs11Libraries.isEmpty()) {
		Text(
			text = stringResource(Res.string.settings_pkcs11_no_libraries),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	} else {
		val reversedEntries = state.customPkcs11Libraries.withIndex().reversed()
		if (reversedEntries.size > Pkcs11ListScrollThreshold) {
			VerticalScrollableColumn(
				modifier = Modifier.fillMaxWidth().heightIn(max = Pkcs11ListMaxHeight),
				contentPadding = PaddingValues(end = 12.dp),
			) {
				Pkcs11LibraryRows(entries = reversedEntries, onFieldChange = onFieldChange)
			}
		} else {
			Pkcs11LibraryRows(entries = reversedEntries, onFieldChange = onFieldChange)
		}
	}
}

/**
 * Render the custom PKCS#11 library rows (already in display order) with a remove action and
 * inter-row spacing.
 *
 * Extracted so both the inline layout (short lists) and the scrollable-box layout (long lists)
 * in [Pkcs11Section] share one row-rendering path.
 *
 * @param entries Indexed libraries in display order; [IndexedValue.index] is the original
 *   position in `customPkcs11Libraries`, used so Remove deletes the correct entry regardless
 *   of the (reversed) display order.
 * @param onFieldChange Edit-state field updater used to apply a removal.
 */
@Composable
private fun Pkcs11LibraryRows(
	entries: List<IndexedValue<CustomPkcs11Library>>,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	entries.forEachIndexed { displayPos, entry ->
		Pkcs11LibraryRow(
			library = entry.value,
			onProtectedPinPadChange = { enabled ->
				onFieldChange {
					it.copy(customPkcs11Libraries = it.customPkcs11Libraries.toMutableList().apply {
						this[entry.index] = this[entry.index].copy(protectedAuthenticationPath = enabled)
					})
				}
			},
			onRemove = {
				onFieldChange {
					it.copy(customPkcs11Libraries = it.customPkcs11Libraries.toMutableList().apply {
						removeAt(entry.index)
					})
				}
			},
		)
		if (displayPos < entries.lastIndex) {
			Spacer(modifier = Modifier.height(4.dp))
		}
	}
}

/**
 * Hint banner shown at the top of the PKCS#11 libraries section.
 *
 * Tells the user about the auto-discovery drop directory and exposes the
 * absolute path as a clickable link rendered on its own line below the tip.
 * Clicking the link invokes [onOpen], typically wired to reveal the folder
 * in the host OS file manager (Explorer / Finder / `xdg-open`).
 *
 * The path lives on a separate line so Compose's word-break rules don't split
 * it mid-way at colons or directory separators — which would otherwise turn
 * a Windows path into "into C:\" / line-break / "Users\Vojta\..." and other
 * awkward layouts depending on the available width.
 *
 * Only rendered when the current platform has a drop-directory concept
 * (i.e. when [resolvePkcs11DropDirectory] returns non-null).  Web targets
 * skip this affordance entirely.
 */
@Composable
private fun Pkcs11DropDirectoryHint(path: String, onOpen: () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(
			text = stringResource(Res.string.settings_pkcs11_drop_dir_hint),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
		val annotated = buildAnnotatedString {
			withLink(
				LinkAnnotation.Clickable(
					tag = "pkcs11-drop-directory",
					styles = TextLinkStyles(
						style = SpanStyle(
							color = LumoTheme.colors.primary,
							textDecoration = TextDecoration.Underline,
							fontWeight = FontWeight.Medium,
						),
					),
					linkInteractionListener = { onOpen() },
				),
			) {
				append(path)
			}
		}
		Text(
			text = annotated,
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.text,
		)
	}
}

/**
 * Single row displaying a registered PKCS#11 library with a protected-pin-pad toggle and a
 * remove button.
 *
 * @param library The library entry to display.
 * @param onProtectedPinPadChange Callback invoked when the user flips the protected-pin-pad
 *   switch; the new value is staged into the edit state and persisted on Save.
 * @param onRemove Callback invoked when the user clicks the remove button.
 */
@Composable
private fun Pkcs11LibraryRow(
	library: CustomPkcs11Library,
	onProtectedPinPadChange: (Boolean) -> Unit,
	onRemove: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(text = library.name, style = LumoTheme.typography.label1)
			Text(
				text = library.path,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Switch(
					checked = library.protectedAuthenticationPath,
					onCheckedChange = onProtectedPinPadChange,
				)
				Text(
					text = stringResource(Res.string.settings_pkcs11_protected_pin_pad_label),
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
			}
		}
		IconButton(
			variant = IconButtonVariant.Ghost,
			onClick = onRemove,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = stringResource(Res.string.settings_pkcs11_remove_library_description, library.name),
				modifier = Modifier.size(16.dp),
			)
		}
	}
}

/**
 * Inline add a row for registering a new PKCS#11 library.
 *
 * @param onAdd Callback invoked with (name, path) when the user confirms the new entry.
 */
@Composable
private fun Pkcs11AddRow(onAdd: (name: String, path: String, protectedPinPad: Boolean) -> Unit) {
	var name by remember { mutableStateOf("") }
	var path by remember { mutableStateOf("") }
	var protectedPinPad by remember { mutableStateOf(false) }
	
	val libraryFilePicker = rememberFilePickerLauncher(
		type = FileKitType.File(extensions = listOf("dll", "so", "dylib")),
	) { file: PlatformFile? ->
		val selected = file?.let { platformFilePath(it) }
		if (selected != null) {
			path = selected
		}
	}
	
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.Bottom,
		) {
			UnderlinedTextField(
				value = name,
				onValueChange = { name = it },
				label = { Text(text = stringResource(Res.string.settings_pkcs11_add_name_label)) },
				placeholder = { Text(text = stringResource(Res.string.settings_pkcs11_add_name_placeholder)) },
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
			UnderlinedTextField(
				value = path,
				onValueChange = { path = it },
				label = { Text(text = stringResource(Res.string.settings_pkcs11_add_path_label)) },
				placeholder = { Text(text = "/path/to/library.so") },
				singleLine = true,
				modifier = Modifier.weight(2f),
				trailingIcon = {
					TooltipBox(
						tooltip = { Tooltip { Text(text = stringResource(Res.string.action_browse)) } },
						state = rememberTooltipState(),
					) {
						IconButton(
							modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
							variant = IconButtonVariant.Ghost,
							onClick = { libraryFilePicker.launch() },
						) {
							Icon(
								painter = painterResource(Res.drawable.icon_folder),
								contentDescription = stringResource(Res.string.settings_pkcs11_browse_library_description),
								modifier = Modifier.size(18.dp),
							)
						}
					}
				},
			)
			Button(
				text = stringResource(Res.string.action_add),
				variant = ButtonVariant.PrimaryOutlined,
				enabled = name.isNotBlank() && path.isNotBlank(),
				onClick = {
					onAdd(name.trim(), path.trim(), protectedPinPad)
					name = ""
					path = ""
					protectedPinPad = false
				},
			)
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(
					text = stringResource(Res.string.settings_pkcs11_protected_pin_pad_label),
					style = LumoTheme.typography.label1,
				)
				InfoTooltip(text = stringResource(Res.string.settings_pkcs11_protected_pin_pad_tooltip))
			}
			Switch(
				checked = protectedPinPad,
				onCheckedChange = { protectedPinPad = it },
			)
		}
	}
}

/**
 * Password text field with a trailing visibility toggle icon.
 *
 * When [hasStoredPassword] is true and the field is empty, a dot placeholder indicates
 * that a password is already stored in the OS credential store. Entering a new
 * value will replace the stored password on save.
 *
 * @param value The current password text.
 * @param onValueChange Called when the password text changes.
 * @param hasStoredPassword Whether a password is already persisted in the credential store.
 */
@Composable
private fun SettingsPasswordField(
	value: String,
	onValueChange: (String) -> Unit,
	hasStoredPassword: Boolean,
) {
	var visible by remember { mutableStateOf(false) }
	
	UnderlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = { Text(text = stringResource(Res.string.label_password)) },
		placeholder = {
			Text(
				text = if (hasStoredPassword) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
				else stringResource(Res.string.label_optional),
			)
		},
		supportingText = if (hasStoredPassword && value.isEmpty()) {
			{ Text(text = stringResource(Res.string.hint_password_stored)) }
		} else {
			null
		},
		singleLine = true,
		visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
		modifier = Modifier.fillMaxWidth(),
		trailingIcon = {
			IconButton(
				variant = IconButtonVariant.Ghost,
				onClick = { visible = !visible },
			) {
				Icon(
					painter = painterResource(
						if (visible) Res.drawable.icon_eye_off else Res.drawable.icon_eye
					),
					contentDescription = if (visible) stringResource(Res.string.action_hide_password) else stringResource(Res.string.action_show_password),
					modifier = Modifier.size(18.dp),
				)
			}
		},
	)
}

/**
 * Appearance > Window section: toggle between custom merged toolbar and native OS title bar.
 *
 * Shown only on Linux JVM desktop where the choice between CSD (undecorated + custom
 * toolbar) and SSD (native decorated window) is meaningful.
 *
 * @param state Current [GlobalConfigEditState].
 * @param onFieldChange Called with a transform to update a single field in the edit state.
 */
@Composable
private fun AppearanceWindowSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(text = stringResource(Res.string.settings_appearance_native_title_bar_label), style = LumoTheme.typography.body1)
			Text(
				text = stringResource(Res.string.settings_appearance_native_title_bar_description),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
		}
		
		Switch(
			checked = state.useNativeTitleBar,
			onCheckedChange = { checked ->
				onFieldChange { it.copy(useNativeTitleBar = checked) }
			},
		)
	}
	
	Spacer(modifier = Modifier.height(8.dp))

	Text(
		text = stringResource(Res.string.settings_appearance_restart_required),
		style = LumoTheme.typography.body2,
		color = LumoTheme.colors.textSecondary,
	)
}

/**
 * Language & Region section: pick a region preset, the UI language, and the date format.
 *
 * The three selectors are layered from coarse to fine. The preset combines a language with its
 * conventional date format in one step; the language and format selectors below override either
 * dimension independently, in which case the preset selector reflects [RegionChoice.Custom]. All
 * three are runtime UI preferences applied immediately by the host (not part of the saved config).
 *
 * @param languageTag The active UI language tag (`null` = system default).
 * @param dateFormat The active UI date format.
 * @param onLanguageChange Called with the chosen language tag (`null` = system default).
 * @param onFormatChange Called with the chosen date format.
 */
@Composable
private fun LanguageRegionSection(
	languageTag: String?,
	dateFormat: DateFormat,
	onLanguageChange: (String?) -> Unit,
	onFormatChange: (DateFormat) -> Unit,
) {
	val currentChoice = RegionChoice.of(languageTag, dateFormat)
	val presetOptions = buildList {
		add(RegionChoice.System)
		RegionPreset.entries.forEach { add(RegionChoice.Preset(it)) }
		if (currentChoice is RegionChoice.Custom) add(RegionChoice.Custom)
	}

	DropdownSelector(
		selected = currentChoice,
		options = presetOptions,
		onSelect = { choice ->
			when (choice) {
				RegionChoice.System -> {
					onLanguageChange(null)
					onFormatChange(DateFormat.SYSTEM)
				}
				is RegionChoice.Preset -> {
					onLanguageChange(choice.value.languageTag)
					onFormatChange(choice.value.dateFormat)
				}
				RegionChoice.Custom, null -> {}
			}
		},
		label = { Text(text = stringResource(Res.string.settings_region_preset_label)) },
		showNullOption = false,
		disabledOptions = setOf(RegionChoice.Custom),
		itemLabel = { it.label() },
		modifier = Modifier.fillMaxWidth(),
	)

	Spacer(modifier = Modifier.height(12.dp))

	DropdownSelector(
		selected = languageTag,
		options = LanguageOptions,
		onSelect = { tag -> onLanguageChange(tag) },
		label = { Text(text = stringResource(Res.string.settings_region_language_label)) },
		nullLabel = stringResource(Res.string.settings_region_system_default),
		itemLabel = { languageEndonym(it) },
		modifier = Modifier.fillMaxWidth(),
	)

	Spacer(modifier = Modifier.height(12.dp))

	DropdownSelector(
		selected = dateFormat,
		options = DateFormat.entries.toList(),
		onSelect = { value -> onFormatChange(value ?: DateFormat.SYSTEM) },
		label = { Text(text = stringResource(Res.string.settings_region_format_label)) },
		showNullOption = false,
		itemLabel = { dateFormatLabel(it) },
		modifier = Modifier.fillMaxWidth(),
	)
}

/** Selectable UI language tags offered in the language dropdown, in display order. */
private val LanguageOptions = listOf("en", "cs", "sk")

/**
 * The native-name (endonym) label for a UI language tag, shown in the language dropdown.
 *
 * Endonyms are intentionally not translated: each language is presented in its own script so a user
 * can recognize their language regardless of the currently active UI locale.
 */
private fun languageEndonym(tag: String): String = when (tag) {
	"en" -> "English"
	"cs" -> "Čeština"
	"sk" -> "Slovenčina"
	else -> tag
}

/**
 * The display label for a [DateFormat] in the format dropdown.
 *
 * [DateFormat.SYSTEM] resolves to a localized "System default" label; every other entry is shown as
 * its language-neutral [DateFormat.displayPattern] (e.g. `dd/mm/yyyy`), which is not translated.
 */
@Composable
private fun dateFormatLabel(format: DateFormat): String = when (format) {
	DateFormat.SYSTEM -> stringResource(Res.string.settings_region_system_default)
	else -> format.displayPattern
}
