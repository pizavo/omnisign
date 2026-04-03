package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.TimestampDialogState
import cz.pizavo.omnisign.ui.model.TimestampType
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_alert_warning
import omnisign.composeapp.generated.resources.icon_check
import omnisign.composeapp.generated.resources.icon_x
import org.jetbrains.compose.resources.painterResource

/**
 * Modal dialog for extending a signed PDF to a higher PAdES level (timestamp / archival).
 *
 * The dialog adapts its content to the current [TimestampDialogState]:
 * - [TimestampDialogState.Ready]: form with timestamp type selector and output path.
 * - [TimestampDialogState.Extending]: progress indicator.
 * - [TimestampDialogState.RevocationWarning]: revocation data warning with abort/continue options.
 * - [TimestampDialogState.Success]: summary of the extension result.
 * - [TimestampDialogState.Error]: error message.
 *
 * @param state Current timestamp dialog state from [cz.pizavo.omnisign.ui.viewmodel.TimestampViewModel].
 * @param onFieldChange Called with a transform to update a field in the [TimestampDialogState.Ready] state.
 * @param onExtend Called when the user clicks the Extend button.
 * @param onAbortRevocation Called when the user clicks Abort on the revocation warning.
 * @param onAcceptRevocation Called when the user clicks Continue on the revocation warning.
 * @param onDismiss Called when the user cancels or closes the dialog.
 */
@Composable
fun TimestampDialog(
	state: TimestampDialogState,
	onFieldChange: ((TimestampDialogState.Ready) -> TimestampDialogState.Ready) -> Unit,
	onExtend: () -> Unit,
	onAbortRevocation: () -> Unit,
	onAcceptRevocation: () -> Unit,
	onDismiss: () -> Unit,
) {
	Dialog(
		onDismissRequest = {
			if (state !is TimestampDialogState.Extending) onDismiss()
		},
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				.widthIn(min = 480.dp, max = 620.dp)
				.heightIn(min = 300.dp, max = 500.dp),
			shape = RoundedCornerShape(16.dp),
			color = LumoTheme.colors.surface,
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				TimestampDialogHeader(
					onClose = onDismiss,
					closeable = state !is TimestampDialogState.Extending,
				)

				HorizontalDivider()

				Box(modifier = Modifier.weight(1f)) {
					when (state) {
						is TimestampDialogState.Idle -> {}
						is TimestampDialogState.Ready -> TimestampFormContent(
							state = state,
							onFieldChange = onFieldChange,
						)
						is TimestampDialogState.Extending -> LoadingContent("Extending document...")
						is TimestampDialogState.RevocationWarning -> TimestampRevocationWarningContent(state)
						is TimestampDialogState.Success -> TimestampSuccessContent(state)
						is TimestampDialogState.Error -> ErrorContent(
							message = state.message,
							details = state.details,
						)
					}
				}

				HorizontalDivider()

				TimestampDialogFooter(
					state = state,
					onExtend = onExtend,
					onAbortRevocation = onAbortRevocation,
					onAcceptRevocation = onAcceptRevocation,
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
private fun TimestampDialogHeader(onClose: () -> Unit, closeable: Boolean) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(text = "Extend Document", style = LumoTheme.typography.h3)
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
 * Form section for configuring the extension operation.
 *
 * Displays a dropdown with [TimestampType] options and an output path field.
 * Types present in [TimestampDialogState.Ready.disabledTypes] are shown but
 * not selectable.
 *
 * @param state Current [TimestampDialogState.Ready] state.
 * @param onFieldChange Called with a transform to update a field.
 */
@Composable
private fun TimestampFormContent(
	state: TimestampDialogState.Ready,
	onFieldChange: ((TimestampDialogState.Ready) -> TimestampDialogState.Ready) -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		DropdownSelector(
			selected = state.timestampType,
			options = TimestampType.entries.toList(),
			onSelect = { type ->
				if (type != null) onFieldChange { it.copy(timestampType = type) }
			},
			label = { Text("Timestamp Type") },
			showNullOption = false,
			disabledOptions = state.disabledTypes,
			itemLabel = { it.label },
			modifier = Modifier.fillMaxWidth(),
		)

		UnderlinedTextField(
			value = state.outputPath,
			onValueChange = { v -> onFieldChange { it.copy(outputPath = v) } },
			singleLine = true,
			label = { Text("Output file path") },
			modifier = Modifier.fillMaxWidth(),
		)

		if (state.timestampType == TimestampType.ARCHIVAL_TIMESTAMP) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Checkbox(
					checked = state.addToRenewalJob,
					onCheckedChange = { checked ->
						onFieldChange { it.copy(addToRenewalJob = checked) }
					},
					enabled = state.coveringRenewalJobName == null,
				)
				Text(text = "Add to renewal job", style = LumoTheme.typography.body2)
				InfoTooltip(
					text = if (state.coveringRenewalJobName != null)
						"Already covered by \"${state.coveringRenewalJobName}\""
					else
						"Set up automatic archival renewal after extending",
				)
			}
		}
	}
}

/**
 * Warning content shown when revocation data could not be obtained during
 * a B-LT extension attempt.
 *
 * @param state The [TimestampDialogState.RevocationWarning] state.
 */
@Composable
private fun TimestampRevocationWarningContent(state: TimestampDialogState.RevocationWarning) {
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
				painter = painterResource(Res.drawable.icon_alert_warning),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				tint = LumoTheme.colors.warning,
			)
			Text(text = "Revocation data unavailable", style = LumoTheme.typography.h4)
		}

		Spacer(modifier = Modifier.height(4.dp))

		Text(
			text = "The extension to B-LT failed because revocation information (CRL/OCSP) " +
					"could not be obtained. You can continue with a B-T extension (signature " +
					"timestamp only, without revocation data), or abort and try again later.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)

		Spacer(modifier = Modifier.height(8.dp))

		state.warnings.forEach { warning ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.Top,
			) {
				Icon(
					painter = painterResource(Res.drawable.icon_alert_warning),
					contentDescription = null,
					modifier = Modifier.padding(top = 3.dp).size(14.dp),
					tint = LumoTheme.colors.warning,
				)
				Text(
					text = warning,
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.warning,
				)
			}
		}
	}
}

/**
 * Success summary shown after a successful extension operation.
 *
 * @param state The [TimestampDialogState.Success] state with result details.
 */
@Composable
private fun TimestampSuccessContent(state: TimestampDialogState.Success) {
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
			Text(text = "Document extended successfully", style = LumoTheme.typography.h4)
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

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(
				text = "New level:",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			Text(text = state.newLevel, style = LumoTheme.typography.body2)
		}

		if (state.warnings.isNotEmpty()) {
			Spacer(modifier = Modifier.height(8.dp))
			state.warnings.forEach { warning ->
				Row(
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					verticalAlignment = Alignment.Top,
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_alert_warning),
						contentDescription = null,
						modifier = Modifier.padding(top = 3.dp).size(14.dp),
						tint = LumoTheme.colors.warning,
					)
					Text(
						text = warning,
						style = LumoTheme.typography.body2,
						color = LumoTheme.colors.warning,
					)
				}
			}
		}
	}
}

/**
 * Footer with Cancel / Extend / Continue / Abort / Close buttons.
 *
 * @param state Current dialog state determining which buttons to show.
 * @param onExtend Called when the Extend button is clicked.
 * @param onAbortRevocation Called when Abort is clicked on the revocation warning.
 * @param onAcceptRevocation Called when Continue is clicked on the revocation warning.
 * @param onDismiss Called when Cancel or Close is clicked.
 */
@Composable
private fun TimestampDialogFooter(
	state: TimestampDialogState,
	onExtend: () -> Unit,
	onAbortRevocation: () -> Unit,
	onAcceptRevocation: () -> Unit,
	onDismiss: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
	) {
		when (state) {
			is TimestampDialogState.Ready -> {
				Button(
					text = "Cancel",
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
				Button(
					text = "Extend",
					variant = ButtonVariant.Primary,
					enabled = state.outputPath.isNotBlank(),
					onClick = onExtend,
				)
			}

			is TimestampDialogState.RevocationWarning -> {
				Button(
					text = "Continue anyway",
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onAcceptRevocation,
				)
				Button(
					text = "Abort",
					variant = ButtonVariant.Primary,
					onClick = onAbortRevocation,
				)
			}

			is TimestampDialogState.Success -> {
				Button(
					text = "Close",
					variant = ButtonVariant.Primary,
					onClick = onDismiss,
				)
			}

			is TimestampDialogState.Error -> {
				Button(
					text = "Close",
					variant = ButtonVariant.SecondaryOutlined,
					onClick = onDismiss,
				)
			}

			else -> {}
		}
	}
}

