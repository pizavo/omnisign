package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.pizavo.omnisign.domain.model.config.EtsiUriHint
import cz.pizavo.omnisign.domain.model.config.SERVICE_STATUS_HINTS
import cz.pizavo.omnisign.domain.model.config.SERVICE_TYPE_HINTS
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.ServiceEditState
import cz.pizavo.omnisign.ui.model.TlBuilderDialogState
import cz.pizavo.omnisign.ui.model.TspEditState
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource

/**
 * Modal dialog for building a custom ETSI TS 119612 Trusted List from scratch.
 *
 * Unlike the CLI's multistep wizard, every field is presented in a single
 * scrollable form. The dialog adapts its content to the current [TlBuilderDialogState]:
 * - [TlBuilderDialogState.Editing]: scheme info, TSP cards with services, output options.
 * - [TlBuilderDialogState.Compiling]: progress spinner.
 * - [TlBuilderDialogState.Success]: summary with the generated file path.
 * - [TlBuilderDialogState.Error]: error message with details.
 *
 * @param state Current builder dialog state from [cz.pizavo.omnisign.ui.viewmodel.TlBuilderViewModel].
 * @param onFieldChange Called with a transform to update a field in the [TlBuilderDialogState.Editing] state.
 * @param onAddTsp Called to append a new empty TSP card.
 * @param onRemoveTsp Called with the TSP index to remove.
 * @param onAddService Called with the TSP index to add a service to.
 * @param onRemoveService Called with (tspIndex, serviceIndex) to remove.
 * @param onCompile Called when the user clicks "Compile & Save".
 * @param onDismiss Called when the user cancels or closes the dialog.
 */
@Composable
fun TlBuilderDialog(
	state: TlBuilderDialogState,
	onFieldChange: ((TlBuilderDialogState.Editing) -> TlBuilderDialogState.Editing) -> Unit,
	onAddTsp: () -> Unit,
	onRemoveTsp: (Int) -> Unit,
	onAddService: (Int) -> Unit,
	onRemoveService: (Int, Int) -> Unit,
	onCompile: () -> Unit,
	onDismiss: () -> Unit,
) {
	Dialog(
		onDismissRequest = {
			if (state !is TlBuilderDialogState.Compiling) onDismiss()
		},
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.widthIn(min = 640.dp, max = 860.dp)
				.heightIn(min = 500.dp, max = 720.dp),
			shape = RoundedCornerShape(16.dp),
			color = LumoTheme.colors.surface,
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				TlBuilderHeader(
					onClose = onDismiss,
					closeable = state !is TlBuilderDialogState.Compiling,
				)

				HorizontalDivider()

				Box(modifier = Modifier.weight(1f)) {
					when (state) {
						is TlBuilderDialogState.Idle -> {}
						is TlBuilderDialogState.Editing -> TlBuilderFormContent(
							state = state,
							onFieldChange = onFieldChange,
							onAddTsp = onAddTsp,
							onRemoveTsp = onRemoveTsp,
							onAddService = onAddService,
							onRemoveService = onRemoveService,
						)

						is TlBuilderDialogState.Compiling -> LoadingContent("Compiling trusted list…")
						is TlBuilderDialogState.Success -> TlBuilderSuccessContent(state)
						is TlBuilderDialogState.Error -> ErrorContent(
							message = state.message,
							details = state.details,
						)
					}
				}

				HorizontalDivider()

				TlBuilderFooter(
					state = state,
					onCompile = onCompile,
					onDismiss = onDismiss,
				)
			}
		}
	}
}

/**
 * Header row with the dialog title and close button.
 *
 * @param onClose Callback invoked when the close button is clicked.
 * @param closeable Whether the close button is enabled.
 */
@Composable
private fun TlBuilderHeader(onClose: () -> Unit, closeable: Boolean) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = "Build Custom Trusted List", style = LumoTheme.typography.h3)
		IconButton(
			variant = IconButtonVariant.Ghost,
			enabled = closeable,
			onClick = onClose,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = "Close",
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

/**
 * Scrollable form for entering scheme information, TSPs, services, and output options.
 */
@Composable
private fun TlBuilderFormContent(
	state: TlBuilderDialogState.Editing,
	onFieldChange: ((TlBuilderDialogState.Editing) -> TlBuilderDialogState.Editing) -> Unit,
	onAddTsp: () -> Unit,
	onRemoveTsp: (Int) -> Unit,
	onAddService: (Int) -> Unit,
	onRemoveService: (Int, Int) -> Unit,
) {
	VerticalScrollableColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(24.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		if (state.error != null) {
			Text(
				text = state.error,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.error,
			)
			Spacer(modifier = Modifier.height(8.dp))
		}

		Text(text = "Scheme Information", style = LumoTheme.typography.h4)
		Spacer(modifier = Modifier.height(4.dp))

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			UnderlinedTextField(
				value = state.name,
				onValueChange = { v -> onFieldChange { it.copy(name = v, error = null) } },
				label = { Text("Name") },
				placeholder = { Text("my-trusted-list") },
				singleLine = true,
				modifier = Modifier.weight(2f),
			)
			UnderlinedTextField(
				value = state.territory,
				onValueChange = { v -> onFieldChange { it.copy(territory = v.take(2).uppercase(), error = null) } },
				label = { Text("Territory") },
				placeholder = { Text("CZ") },
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
		}

		Spacer(modifier = Modifier.height(4.dp))

		UnderlinedTextField(
			value = state.schemeOperatorName,
			onValueChange = { v -> onFieldChange { it.copy(schemeOperatorName = v, error = null) } },
			label = { Text("Scheme operator name") },
			placeholder = { Text("Organisation publishing this trusted list") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)

		Spacer(modifier = Modifier.height(16.dp))
		HorizontalDivider()
		Spacer(modifier = Modifier.height(12.dp))

		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(text = "Trust Service Providers", style = LumoTheme.typography.h4)
			Button(
				text = "Add TSP",
				variant = ButtonVariant.PrimaryOutlined,
				onClick = onAddTsp,
			)
		}

		if (state.tsps.isEmpty()) {
			Spacer(modifier = Modifier.height(8.dp))
			Text(
				text = "No TSPs added yet. Click `Add TSP` to get started.",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
		}

		state.tsps.forEachIndexed { tspIndex, tsp ->
			Spacer(modifier = Modifier.height(8.dp))
			TspCard(
				tsp = tsp,
				tspIndex = tspIndex,
				onFieldChange = onFieldChange,
				onRemove = { onRemoveTsp(tspIndex) },
				onAddService = { onAddService(tspIndex) },
				onRemoveService = { svcIndex -> onRemoveService(tspIndex, svcIndex) },
			)
		}

		Spacer(modifier = Modifier.height(16.dp))
		HorizontalDivider()
		Spacer(modifier = Modifier.height(12.dp))

		Text(text = "Output", style = LumoTheme.typography.h4)
		Spacer(modifier = Modifier.height(4.dp))

		UnderlinedTextField(
			value = state.outputPath,
			onValueChange = { v -> onFieldChange { it.copy(outputPath = v, error = null) } },
			label = { Text("Output file path") },
			placeholder = { Text("/path/to/output.xml") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)

		Spacer(modifier = Modifier.height(8.dp))

		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Checkbox(
				checked = state.registerAfterCompile,
				onCheckedChange = { checked -> onFieldChange { it.copy(registerAfterCompile = checked) } },
			)
			Text(
				text = "Register as a custom trusted list source after compiling",
				style = LumoTheme.typography.body2,
			)
		}
	}
}

/**
 * Expandable card for a single Trust Service Provider with inline service editing.
 */
@Composable
private fun TspCard(
	tsp: TspEditState,
	tspIndex: Int,
	onFieldChange: ((TlBuilderDialogState.Editing) -> TlBuilderDialogState.Editing) -> Unit,
	onRemove: () -> Unit,
	onAddService: () -> Unit,
	onRemoveService: (Int) -> Unit,
) {
	val chevronRotation = if (tsp.expanded) 0f else -90f

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(8.dp),
		color = LumoTheme.colors.background,
	) {
		Column(modifier = Modifier.padding(12.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(
					modifier = Modifier
						.weight(1f)
						.clip(RoundedCornerShape(4.dp))
						.clickable {
							onFieldChange { editing ->
								editing.copy(
									tsps = editing.tsps.mapIndexed { i, t ->
										if (i == tspIndex) t.copy(expanded = !t.expanded) else t
									}
								)
							}
						}
						.padding(vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_chevron_down),
						contentDescription = if (tsp.expanded) "Collapse" else "Expand",
						modifier = Modifier
							.size(14.dp)
							.graphicsLayer(rotationZ = chevronRotation),
						tint = LumoTheme.colors.textSecondary,
					)
					Text(
						text = tsp.name.ifBlank { "TSP #${tspIndex + 1}" },
						style = LumoTheme.typography.label1,
					)
					if (tsp.services.isNotEmpty()) {
						Text(
							text = "(${tsp.services.size} service${if (tsp.services.size != 1) "s" else ""})",
							style = LumoTheme.typography.body2,
							color = LumoTheme.colors.textSecondary,
						)
					}
				}

				IconButton(variant = IconButtonVariant.Ghost, onClick = onRemove) {
					Icon(
						painter = painterResource(Res.drawable.icon_x),
						contentDescription = "Remove TSP",
						modifier = Modifier.size(16.dp),
					)
				}
			}

			if (tsp.expanded) {
				Spacer(modifier = Modifier.height(8.dp))

				UnderlinedTextField(
					value = tsp.name,
					onValueChange = { v ->
						onFieldChange { editing ->
							editing.copy(
								tsps = editing.tsps.mapIndexed { i, t ->
									if (i == tspIndex) t.copy(name = v) else t
								},
								error = null,
							)
						}
					},
					label = { Text("TSP name") },
					placeholder = { Text("Official name") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)

				Spacer(modifier = Modifier.height(4.dp))

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					UnderlinedTextField(
						value = tsp.tradeName,
						onValueChange = { v ->
							onFieldChange { editing ->
								editing.copy(
									tsps = editing.tsps.mapIndexed { i, t ->
										if (i == tspIndex) t.copy(tradeName = v) else t
									},
								)
							}
						},
						label = { Text("Trade name (optional)") },
						singleLine = true,
						modifier = Modifier.weight(1f),
					)
					UnderlinedTextField(
						value = tsp.infoUrl,
						onValueChange = { v ->
							onFieldChange { editing ->
								editing.copy(
									tsps = editing.tsps.mapIndexed { i, t ->
										if (i == tspIndex) t.copy(infoUrl = v) else t
									},
								)
							}
						},
						label = { Text("Info URL (optional)") },
						placeholder = { Text("https://…") },
						singleLine = true,
						modifier = Modifier.weight(1f),
					)
				}

				Spacer(modifier = Modifier.height(12.dp))

				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(text = "Services", style = LumoTheme.typography.label1)
					Button(
						text = "Add Service",
						variant = ButtonVariant.SecondaryOutlined,
						onClick = onAddService,
					)
				}

				tsp.services.forEachIndexed { svcIndex, svc ->
					Spacer(modifier = Modifier.height(8.dp))
					ServiceRow(
						service = svc,
						tspIndex = tspIndex,
						serviceIndex = svcIndex,
						onFieldChange = onFieldChange,
						onRemove = { onRemoveService(svcIndex) },
					)
				}

				if (tsp.services.isEmpty()) {
					Spacer(modifier = Modifier.height(4.dp))
					Text(
						text = "No services yet.",
						style = LumoTheme.typography.body2,
						color = LumoTheme.colors.textSecondary,
					)
				}
			}
		}
	}
}

/**
 * Inline row for editing a single trust service within a TSP card.
 */
@Composable
private fun ServiceRow(
	service: ServiceEditState,
	tspIndex: Int,
	serviceIndex: Int,
	onFieldChange: ((TlBuilderDialogState.Editing) -> TlBuilderDialogState.Editing) -> Unit,
	onRemove: () -> Unit,
) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(6.dp),
		color = LumoTheme.colors.surface,
	) {
		Column(modifier = Modifier.padding(10.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					text = service.name.ifBlank { "Service #${serviceIndex + 1}" },
					style = LumoTheme.typography.label1,
				)
				IconButton(variant = IconButtonVariant.Ghost, onClick = onRemove) {
					Icon(
						painter = painterResource(Res.drawable.icon_x),
						contentDescription = "Remove service",
						modifier = Modifier.size(14.dp),
					)
				}
			}

			UnderlinedTextField(
				value = service.name,
				onValueChange = { v ->
					onFieldChange { editing ->
						editing.copy(
							tsps = editing.tsps.mapIndexed { i, tsp ->
								if (i == tspIndex) tsp.copy(
									services = tsp.services.mapIndexed { j, s ->
										if (j == serviceIndex) s.copy(name = v) else s
									}
								) else tsp
							},
							error = null,
						)
					}
				},
				label = { Text("Service name") },
				singleLine = true,
				modifier = Modifier.fillMaxWidth(),
			)

			Spacer(modifier = Modifier.height(4.dp))

			EtsiUriField(
				value = service.typeIdentifier,
				onValueChange = { v ->
					onFieldChange { editing ->
						editing.copy(
							tsps = editing.tsps.mapIndexed { i, tsp ->
								if (i == tspIndex) tsp.copy(
									services = tsp.services.mapIndexed { j, s ->
										if (j == serviceIndex) s.copy(typeIdentifier = v) else s
									}
								) else tsp
							},
							error = null,
						)
					}
				},
				label = "Type identifier",
				placeholder = "http://uri.etsi.org/TrstSvc/Svctype/…",
				hints = SERVICE_TYPE_HINTS,
			)

			Spacer(modifier = Modifier.height(4.dp))

			EtsiUriField(
				value = service.status,
				onValueChange = { v ->
					onFieldChange { editing ->
						editing.copy(
							tsps = editing.tsps.mapIndexed { i, tsp ->
								if (i == tspIndex) tsp.copy(
									services = tsp.services.mapIndexed { j, s ->
										if (j == serviceIndex) s.copy(status = v) else s
									}
								) else tsp
							},
							error = null,
						)
					}
				},
				label = "Status",
				placeholder = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/…",
				hints = SERVICE_STATUS_HINTS,
			)

			Spacer(modifier = Modifier.height(4.dp))

			ServiceCertificateField(
				value = service.certificatePath,
				onValueChange = { v ->
					onFieldChange { editing ->
						editing.copy(
							tsps = editing.tsps.mapIndexed { i, tsp ->
								if (i == tspIndex) tsp.copy(
									services = tsp.services.mapIndexed { j, s ->
										if (j == serviceIndex) s.copy(certificatePath = v) else s
									}
								) else tsp
							},
							error = null,
						)
					}
				},
			)
		}
	}
}

/**
 * Text field with an ETSI URI hint dropdown.
 *
 * The field is editable (supports custom URIs) and displays a dropdown
 * popup with common ETSI URIs when the trailing chevron is clicked.
 */
@Composable
private fun EtsiUriField(
	value: String,
	onValueChange: (String) -> Unit,
	label: String,
	placeholder: String,
	hints: List<EtsiUriHint>,
) {
	var showDropdown by remember { mutableStateOf(false) }

	Column(modifier = Modifier.fillMaxWidth()) {
		UnderlinedTextField(
			value = value,
			onValueChange = onValueChange,
			label = { Text(label) },
			placeholder = { Text(placeholder) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			trailingIcon = {
				TooltipBox(
					tooltip = { Tooltip { Text("Show common URIs") } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { showDropdown = !showDropdown },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_chevron_down),
							contentDescription = "Show URI hints",
							modifier = Modifier.size(16.dp),
						)
					}
				}
			},
		)

		if (showDropdown) {
			Surface(
				shape = RoundedCornerShape(8.dp),
				color = LumoTheme.colors.surface,
				shadowElevation = 4.dp,
			) {
				Column(modifier = Modifier.fillMaxWidth()) {
					hints.forEach { hint ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									onValueChange(hint.uri)
									showDropdown = false
								}
								.padding(horizontal = 12.dp, vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column {
								Text(text = hint.label, style = LumoTheme.typography.body2)
								Text(
									text = hint.uri,
									style = LumoTheme.typography.body2,
									color = LumoTheme.colors.textSecondary,
								)
							}
						}
					}
				}
			}
		}
	}
}

/**
 * Certificate path text field with a file picker trailing icon.
 */
@Composable
private fun ServiceCertificateField(
	value: String,
	onValueChange: (String) -> Unit,
) {
	val certPicker = rememberFilePickerLauncher(
		type = FileKitType.File(extensions = listOf("pem", "der", "crt", "cer")),
	) { file: PlatformFile? ->
		if (file != null) {
			onValueChange(platformFilePath(file) ?: file.name)
		}
	}

	UnderlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = { Text("Certificate path") },
		placeholder = { Text("/path/to/certificate.pem") },
		singleLine = true,
		modifier = Modifier.fillMaxWidth(),
		trailingIcon = {
			TooltipBox(
				tooltip = { Tooltip { Text("Browse") } },
				state = rememberTooltipState(),
			) {
				IconButton(
					modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
					variant = IconButtonVariant.Ghost,
					onClick = { certPicker.launch() },
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_folder),
						contentDescription = "Browse for certificate",
						modifier = Modifier.size(18.dp),
					)
				}
			}
		},
	)
}

/**
 * Success summary shown after a successful compilation.
 */
@Composable
private fun TlBuilderSuccessContent(state: TlBuilderDialogState.Success) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_check),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				tint = LumoTheme.colors.success,
			)
			Text(text = "Trusted list compiled successfully", style = LumoTheme.typography.h4)
		}

		Spacer(modifier = Modifier.height(8.dp))

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(
				text = "Output file:",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			Text(text = state.outputFile, style = LumoTheme.typography.body2)
		}

		if (state.tlConfig != null) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					text = "Registered as:",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
				Text(text = state.tlConfig.name, style = LumoTheme.typography.body2)
			}
		} else {
			Text(
				text = "Not registered — you can register it manually later.",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
		}
	}
}

/**
 * Footer with Cancel / Compile & Save / Close buttons.
 */
@Composable
private fun TlBuilderFooter(
	state: TlBuilderDialogState,
	onCompile: () -> Unit,
	onDismiss: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
	) {
		when (state) {
			is TlBuilderDialogState.Editing -> {
				Button(
					text = "Cancel",
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
				Button(
					text = "Compile & Save",
					variant = ButtonVariant.Primary,
					onClick = onCompile,
				)
			}

			is TlBuilderDialogState.Success,
			is TlBuilderDialogState.Error -> {
				Button(
					text = "Close",
					variant = ButtonVariant.Primary,
					onClick = onDismiss,
				)
			}

			else -> {}
		}
	}
}



