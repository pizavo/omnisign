package cz.pizavo.omnisign.ui.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.pizavo.omnisign.domain.model.config.CustomPkcs11Library
import cz.pizavo.omnisign.domain.model.config.enums.AlgorithmConstraintLevel
import cz.pizavo.omnisign.domain.model.config.enums.EncryptionAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.ValidationPolicyType
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import cz.pizavo.omnisign.ui.model.SettingsCategory
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource

private val NavPanelWidth = 220.dp
private val NavItemShape = RoundedCornerShape(6.dp)

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
 */
@Composable
fun SettingsDialog(
	state: GlobalConfigEditState,
	hasChanges: Boolean,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
	onSave: () -> Unit,
	onDismiss: () -> Unit,
	onBuildTl: (() -> Unit)? = null,
) {
	var selectedCategory by remember { mutableStateOf(SettingsCategory.SigningDefaults) }
	
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.widthIn(min = 700.dp, max = 920.dp)
				.heightIn(min = 500.dp, max = 720.dp),
			shape = RoundedCornerShape(16.dp),
			color = LumoTheme.colors.surface,
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				SettingsHeader(onClose = onDismiss)
				
				HorizontalDivider()
				
				Row(modifier = Modifier.weight(1f)) {
					SettingsNavPanel(
						selected = selectedCategory,
						onSelect = { selectedCategory = it },
					)
					
					VerticalDivider()
					
					SettingsContentPanel(
						category = selectedCategory,
						state = state,
						onFieldChange = onFieldChange,
						onBuildTl = onBuildTl,
					)
				}
				
				HorizontalDivider()
				
				SettingsFooter(saving = state.saving, hasChanges = hasChanges, onCancel = onDismiss, onSave = onSave)
			}
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
		Text(text = "Settings", style = LumoTheme.typography.h3)
		IconButton(
			variant = IconButtonVariant.Ghost,
			onClick = onClose,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = "Close settings",
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
 */
@Composable
private fun SettingsNavPanel(
	selected: SettingsCategory,
	onSelect: (SettingsCategory) -> Unit,
) {
	var expandedGroups by remember {
		mutableStateOf(setOf(SettingsCategory.groups.first()))
	}
	
	if (selected.parent != null && selected.parent !in expandedGroups) {
		expandedGroups = expandedGroups + selected.parent
	}
	
	Column(
		modifier = Modifier
			.width(NavPanelWidth)
			.fillMaxHeight()
			.verticalScroll(rememberScrollState())
			.padding(8.dp),
	) {
		SettingsCategory.groups.forEach { group ->
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
			contentDescription = if (isExpanded) "Collapse" else "Expand",
			modifier = Modifier
				.size(14.dp)
				.graphicsLayer(rotationZ = chevronRotation),
			tint = textColor,
		)
		Text(
			text = category.label,
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
			text = category.label,
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
 */
@Composable
private fun SettingsContentPanel(
	category: SettingsCategory,
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
	onBuildTl: (() -> Unit)? = null,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(24.dp),
	) {
		if (state.error != null) {
			Text(
				text = state.error,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.error,
			)
			Spacer(modifier = Modifier.height(8.dp))
		}
		
		Text(text = category.label, style = LumoTheme.typography.h3)
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = category.description,
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
			SettingsCategory.ValidationPolicy -> ValidationPolicySection(state = state, onFieldChange = onFieldChange)
			
			SettingsCategory.AlgorithmConstraints -> AlgorithmConstraintsSection(
				state = state,
				onFieldChange = onFieldChange
			)
			
			SettingsCategory.TrustedCertificates -> TrustedCertificatesSection(
				certificates = state.trustedCertificates,
				onAdd = { cert ->
					onFieldChange {
						it.copy(trustedCertificates = it.trustedCertificates.filter { c -> c.name != cert.name } + cert)
					}
				},
				onRemove = { index ->
					onFieldChange {
						it.copy(trustedCertificates = it.trustedCertificates.toMutableList().apply { removeAt(index) })
					}
				},
				addError = state.certAddError,
				onClearError = { onFieldChange { it.copy(certAddError = null) } },
				onError = { message -> onFieldChange { it.copy(certAddError = message) } },
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
 */
@Composable
private fun SettingsFooter(
	saving: Boolean,
	hasChanges: Boolean,
	onCancel: () -> Unit,
	onSave: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
	) {
		Button(
			text = "Cancel",
			variant = ButtonVariant.Ghost,
			onClick = onCancel,
		)
		Button(
			text = "Save",
			variant = ButtonVariant.Primary,
			enabled = hasChanges && !saving,
			loading = saving,
			onClick = onSave,
		)
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
		label = { Text(text = "Hash algorithm") },
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
		label = { Text(text = "Encryption algorithm") },
		nullLabel = "Auto-detect from certificate",
		disabledOptions = state.disabledEncryptionAlgorithms,
		itemLabel = { it.name },
		modifier = Modifier.fillMaxWidth(),
	)
	
	Spacer(modifier = Modifier.height(12.dp))

	Text(text = "Timestamp level", style = LumoTheme.typography.label1)
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
		Text(text = "Signature timestamp", style = LumoTheme.typography.body2)
		InfoTooltip(text = "Produces PAdES BASELINE B-LT")
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
		Text(text = "Archival timestamp", style = LumoTheme.typography.body2)
		InfoTooltip(text = "Produces PAdES BASELINE B-LTA")
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
	Text(text = "Disabled hash algorithms", style = LumoTheme.typography.label1)
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
	
	Text(text = "Disabled encryption algorithms", style = LumoTheme.typography.label1)
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
		Text(text = "Enable timestamp server", style = LumoTheme.typography.label1)
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
			label = { Text(text = "URL") },
			placeholder = { Text(text = "https://tsa.example.com/tsr") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		
		Spacer(modifier = Modifier.height(8.dp))
		
		UnderlinedTextField(
			value = state.timestampUsername,
			onValueChange = { value -> onFieldChange { it.copy(timestampUsername = value) } },
			label = { Text(text = "Username") },
			placeholder = { Text(text = "Optional") },
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
			label = { Text(text = "Timeout (ms)") },
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
		label = { Text(text = "OCSP timeout (ms)") },
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
		label = { Text(text = "CRL timeout (ms)") },
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
) {
	DropdownSelector(
		selected = state.validationPolicyType,
		options = ValidationPolicyType.entries.toList(),
		onSelect = { value ->
			onFieldChange { it.copy(validationPolicyType = value ?: ValidationPolicyType.DEFAULT_ETSI) }
		},
		label = { Text(text = "Validation policy") },
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
			label = { Text(text = "Custom policy file path") },
			placeholder = { Text(text = "/path/to/policy.xml") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			trailingIcon = {
				TooltipBox(
					tooltip = { Tooltip { Text(text = "Browse") } },
					state = rememberTooltipState()
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { policyFilePicker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_folder),
							contentDescription = "Browse for policy file",
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
		Text(text = "Check certificate revocation", style = LumoTheme.typography.label1)
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
		Text(text = "Use EU List of Trusted Lists", style = LumoTheme.typography.label1)
		Switch(
			checked = state.useEuLotl,
			onCheckedChange = { value -> onFieldChange { it.copy(useEuLotl = value) } },
		)
	}
}

/**
 * Algorithm constraint level selectors for validation.
 */
@Composable
private fun AlgorithmConstraintsSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	DropdownSelector(
		selected = state.algoExpirationLevel,
		options = AlgorithmConstraintLevel.entries.toList(),
		onSelect = { value ->
			onFieldChange { it.copy(algoExpirationLevel = value ?: AlgorithmConstraintLevel.FAIL) }
		},
		label = { Text(text = "Expiration level (before policy update)") },
		showNullOption = false,
		itemLabel = { it.name },
		modifier = Modifier.fillMaxWidth(),
	)
	
	Spacer(modifier = Modifier.height(8.dp))
	
	DropdownSelector(
		selected = state.algoExpirationLevelAfterUpdate,
		options = AlgorithmConstraintLevel.entries.toList(),
		onSelect = { value ->
			onFieldChange {
				it.copy(algoExpirationLevelAfterUpdate = value ?: AlgorithmConstraintLevel.WARN)
			}
		},
		label = { Text(text = "Expiration level (after policy update)") },
		showNullOption = false,
		itemLabel = { it.name },
		modifier = Modifier.fillMaxWidth(),
	)
}

/**
 * PKCS#11 middleware libraries section with add/remove support.
 */
@Composable
private fun Pkcs11Section(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	if (state.customPkcs11Libraries.isEmpty()) {
		Text(
			text = "No custom PKCS#11 libraries registered.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	} else {
		state.customPkcs11Libraries.forEachIndexed { index, lib ->
			Pkcs11LibraryRow(
				library = lib,
				onRemove = {
					onFieldChange {
						it.copy(customPkcs11Libraries = it.customPkcs11Libraries.toMutableList().apply {
							removeAt(index)
						})
					}
				},
			)
			if (index < state.customPkcs11Libraries.lastIndex) {
				Spacer(modifier = Modifier.height(4.dp))
			}
		}
	}
	
	Spacer(modifier = Modifier.height(12.dp))
	
	Pkcs11AddRow(
		onAdd = { name, path ->
			onFieldChange {
				it.copy(
					customPkcs11Libraries = it.customPkcs11Libraries + CustomPkcs11Library(
						name = name,
						path = path,
					)
				)
			}
		},
	)
}

/**
 * Single row displaying a registered PKCS#11 library with a remove button.
 *
 * @param library The library entry to display.
 * @param onRemove Callback invoked when the user clicks the remove button.
 */
@Composable
private fun Pkcs11LibraryRow(
	library: CustomPkcs11Library,
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
		}
		IconButton(
			variant = IconButtonVariant.Ghost,
			onClick = onRemove,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = "Remove ${library.name}",
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
private fun Pkcs11AddRow(onAdd: (name: String, path: String) -> Unit) {
	var name by remember { mutableStateOf("") }
	var path by remember { mutableStateOf("") }
	
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = name,
			onValueChange = { name = it },
			label = { Text(text = "Name") },
			placeholder = { Text(text = "Label") },
			singleLine = true,
			modifier = Modifier.weight(1f),
		)
		UnderlinedTextField(
			value = path,
			onValueChange = { path = it },
			label = { Text(text = "Path") },
			placeholder = { Text(text = "/path/to/library.so") },
			singleLine = true,
			modifier = Modifier.weight(2f),
		)
		Button(
			text = "Add",
			variant = ButtonVariant.PrimaryOutlined,
			enabled = name.isNotBlank() && path.isNotBlank(),
			onClick = {
				onAdd(name.trim(), path.trim())
				name = ""
				path = ""
			},
		)
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
		label = { Text(text = "Password") },
		placeholder = {
			Text(
				text = if (hasStoredPassword) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
				else "Optional",
			)
		},
		supportingText = if (hasStoredPassword && value.isEmpty()) {
			{ Text(text = "Password stored — enter a new value to replace") }
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
					contentDescription = if (visible) "Hide password" else "Show password",
					modifier = Modifier.size(18.dp),
				)
			}
		},
	)
}

