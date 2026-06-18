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
import cz.pizavo.omnisign.domain.model.config.EtsiUriHint
import cz.pizavo.omnisign.domain.model.config.SERVICE_STATUS_HINTS
import cz.pizavo.omnisign.domain.model.config.SERVICE_TYPE_HINTS
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.ServiceEditState
import cz.pizavo.omnisign.ui.model.TlBuilderDialogState
import cz.pizavo.omnisign.ui.model.TspEditState
import cz.pizavo.omnisign.ui.model.resolve
import cz.pizavo.omnisign.ui.platform.VerticalScrollableColumn
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
		modifier = Modifier
			.widthIn(min = 640.dp, max = 860.dp)
			.heightIn(min = 500.dp, max = 720.dp),
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

					is TlBuilderDialogState.Compiling -> LoadingContent(stringResource(Res.string.tlbuilder_compiling))
					is TlBuilderDialogState.Success -> TlBuilderSuccessContent(state)
					is TlBuilderDialogState.Error -> ErrorContent(error = state.content)
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
		Text(text = stringResource(Res.string.tlbuilder_dialog_title), style = LumoTheme.typography.h3)
		IconButton(
			variant = IconButtonVariant.Ghost,
			enabled = closeable,
			onClick = onClose,
		) {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = stringResource(Res.string.action_close),
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
				text = state.error.resolve(),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.error,
			)
			Spacer(modifier = Modifier.height(8.dp))
		}

		Text(text = stringResource(Res.string.tlbuilder_scheme_information), style = LumoTheme.typography.h4)
		Spacer(modifier = Modifier.height(4.dp))

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			UnderlinedTextField(
				value = state.name,
				onValueChange = { v -> onFieldChange { it.copy(name = v, error = null) } },
				label = { Text(stringResource(Res.string.tlbuilder_field_name)) },
				placeholder = { Text(stringResource(Res.string.tlbuilder_field_name_placeholder)) },
				singleLine = true,
				modifier = Modifier.weight(2f),
			)
			UnderlinedTextField(
				value = state.territory,
				onValueChange = { v -> onFieldChange { it.copy(territory = v.take(2).uppercase(), error = null) } },
				label = { Text(stringResource(Res.string.tlbuilder_field_territory)) },
				placeholder = { Text(stringResource(Res.string.tlbuilder_field_territory_placeholder)) },
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
		}

		Spacer(modifier = Modifier.height(4.dp))

		UnderlinedTextField(
			value = state.schemeOperatorName,
			onValueChange = { v -> onFieldChange { it.copy(schemeOperatorName = v, error = null) } },
			label = { Text(stringResource(Res.string.tlbuilder_field_scheme_operator_name)) },
			placeholder = { Text(stringResource(Res.string.tlbuilder_field_scheme_operator_name_placeholder)) },
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
			Text(text = stringResource(Res.string.tlbuilder_trust_service_providers), style = LumoTheme.typography.h4)
			Button(
				text = stringResource(Res.string.tlbuilder_add_tsp),
				variant = ButtonVariant.PrimaryOutlined,
				onClick = onAddTsp,
			)
		}

		if (state.tsps.isEmpty()) {
			Spacer(modifier = Modifier.height(8.dp))
			Text(
				text = stringResource(Res.string.tlbuilder_no_tsps),
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

		Text(text = stringResource(Res.string.tlbuilder_output), style = LumoTheme.typography.h4)
		Spacer(modifier = Modifier.height(4.dp))

		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Checkbox(
				checked = state.registerAfterCompile,
				onCheckedChange = { checked -> onFieldChange { it.copy(registerAfterCompile = checked) } },
			)
			Text(
				text = stringResource(Res.string.tlbuilder_register_after_compile),
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
						contentDescription = if (tsp.expanded) stringResource(Res.string.action_collapse) else stringResource(Res.string.action_expand),
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
						contentDescription = stringResource(Res.string.tlbuilder_remove_tsp),
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
					label = { Text(stringResource(Res.string.tlbuilder_field_tsp_name)) },
					placeholder = { Text(stringResource(Res.string.tlbuilder_field_tsp_name_placeholder)) },
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
						label = { Text(stringResource(Res.string.tlbuilder_field_trade_name)) },
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
						label = { Text(stringResource(Res.string.tlbuilder_field_info_url)) },
						placeholder = { Text(stringResource(Res.string.tlbuilder_field_info_url_placeholder)) },
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
					Text(text = stringResource(Res.string.tlbuilder_services), style = LumoTheme.typography.label1)
					Button(
						text = stringResource(Res.string.tlbuilder_add_service),
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
						text = stringResource(Res.string.tlbuilder_no_services),
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
						contentDescription = stringResource(Res.string.tlbuilder_remove_service),
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
				label = { Text(stringResource(Res.string.tlbuilder_field_service_name)) },
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
				label = stringResource(Res.string.tlbuilder_field_type_identifier),
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
				label = stringResource(Res.string.tlbuilder_field_status),
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
					tooltip = { Tooltip { Text(stringResource(Res.string.tlbuilder_show_common_uris)) } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { showDropdown = !showDropdown },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_chevron_down),
							contentDescription = stringResource(Res.string.tlbuilder_show_uri_hints),
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
		label = { Text(stringResource(Res.string.tlbuilder_field_certificate_path)) },
		placeholder = { Text("/path/to/certificate.pem") },
		singleLine = true,
		modifier = Modifier.fillMaxWidth(),
		trailingIcon = {
			TooltipBox(
				tooltip = { Tooltip { Text(stringResource(Res.string.action_browse)) } },
				state = rememberTooltipState(),
			) {
				IconButton(
					modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
					variant = IconButtonVariant.Ghost,
					onClick = { certPicker.launch() },
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_folder),
						contentDescription = stringResource(Res.string.tlbuilder_browse_for_certificate),
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
			Text(text = stringResource(Res.string.tlbuilder_compiled_successfully), style = LumoTheme.typography.h4)
		}

		Spacer(modifier = Modifier.height(8.dp))

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(
				text = stringResource(Res.string.label_output_file),
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			Text(text = state.outputFile, style = LumoTheme.typography.body2)
		}

		if (state.tlConfig != null) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					text = stringResource(Res.string.tlbuilder_registered_as_label),
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
				Text(text = state.tlConfig.name, style = LumoTheme.typography.body2)
			}
		} else {
			Text(
				text = stringResource(Res.string.tlbuilder_not_registered),
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
					text = stringResource(Res.string.action_cancel),
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
				Button(
					text = stringResource(Res.string.tlbuilder_compile_and_save),
					variant = ButtonVariant.Primary,
					onClick = onCompile,
				)
			}

			is TlBuilderDialogState.Success,
			is TlBuilderDialogState.Error -> {
				Button(
					text = stringResource(Res.string.action_close),
					variant = ButtonVariant.Primary,
					onClick = onDismiss,
				)
			}

			else -> {}
		}
	}
}



