package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.result.label
import cz.pizavo.omnisign.domain.model.value.formatDateTime
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings section for configuring the OS-level daily renewal scheduler.
 *
 * When the executable path was auto-detected from the running process, it is
 * displayed as a read-only information line and the user does not need to
 * configure anything. When auto-detection is unavailable (e.g. launched via
 * `java -jar`), a manual text field with a file picker is shown as a fallback.
 *
 * @param state Current global config edit state containing scheduler fields.
 * @param onFieldChange Called with a transform to update the edit state.
 */
@Composable
fun SchedulerSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Icon(
			painter = painterResource(Res.drawable.icon_circle_filled),
			contentDescription = null,
			tint = if (state.schedulerInstalled) LumoTheme.colors.success
			else LumoTheme.colors.textDisabled,
			modifier = Modifier.size(10.dp),
		)
		Text(
			text = if (state.schedulerInstalled) stringResource(Res.string.scheduler_installed)
			else stringResource(Res.string.scheduler_not_installed),
			style = LumoTheme.typography.label1,
			color = if (state.schedulerInstalled) LumoTheme.colors.success
			else LumoTheme.colors.textSecondary,
		)
		InfoTooltip(
			text = stringResource(Res.string.scheduler_auto_install_tooltip),
		)
	}

	state.renewalRunRecord?.let { record ->
		if (state.renewalJobs.isNotEmpty() || state.schedulerInstalled) {
			Spacer(modifier = Modifier.height(12.dp))
			Text(
				text = "Last successful run: ${record.lastSuccessAt?.formatDateTime() ?: "never"}",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			Spacer(modifier = Modifier.height(4.dp))
			Text(
				text = "Last run: ${record.lastRunAt.formatDateTime()} — ${record.outcome.label} " +
						"(checked ${record.checked}, renewed ${record.renewed}, errors ${record.errors})",
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.textSecondary,
			)
			if (record.failuresSinceSuccess > 0) {
				Spacer(modifier = Modifier.height(4.dp))
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Icon(
						painter = painterResource(Res.drawable.icon_alert_warning),
						contentDescription = null,
						tint = LumoTheme.colors.warning,
						modifier = Modifier.size(14.dp),
					)
					Text(
						text = "${record.failuresSinceSuccess} unsuccessful run(s) since the last success.",
						style = LumoTheme.typography.body2,
						color = LumoTheme.colors.warning,
					)
				}
			}
		}
	}

	Spacer(modifier = Modifier.height(16.dp))

	if (state.schedulerAutoDetectedPath != null) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = stringResource(Res.string.scheduler_executable_label),
				style = LumoTheme.typography.label1,
				color = LumoTheme.colors.textSecondary,
			)
			Text(
				text = state.schedulerAutoDetectedPath,
				style = LumoTheme.typography.body2,
			)
		}
	} else {
		val cliFilePicker = rememberFilePickerLauncher(
			type = FileKitType.File(),
		) { file: PlatformFile? ->
			val path = file?.let { platformFilePath(it) }
			if (path != null) {
				onFieldChange { it.copy(schedulerCliPath = path) }
			}
		}

		UnderlinedTextField(
			value = state.schedulerCliPath,
			onValueChange = { value -> onFieldChange { it.copy(schedulerCliPath = value) } },
			label = { Text(text = stringResource(Res.string.scheduler_cli_path_label)) },
			placeholder = { Text(text = stringResource(Res.string.scheduler_cli_path_placeholder)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			trailingIcon = {
				TooltipBox(
					tooltip = { Tooltip { Text(text = stringResource(Res.string.action_browse)) } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { cliFilePicker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_folder),
							contentDescription = stringResource(Res.string.scheduler_browse_content_desc),
							modifier = Modifier.size(18.dp),
						)
					}
				}
			},
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = state.schedulerHour,
			onValueChange = { value ->
				if (value.all { c -> c.isDigit() } && value.length <= 2) {
					onFieldChange { it.copy(schedulerHour = value) }
				}
			},
			label = { Text(text = stringResource(Res.string.scheduler_hour_label)) },
			isError = !state.isSchedulerHourValid,
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.width(100.dp),
		)
		UnderlinedTextField(
			value = state.schedulerMinute,
			onValueChange = { value ->
				if (value.all { c -> c.isDigit() } && value.length <= 2) {
					onFieldChange { it.copy(schedulerMinute = value) }
				}
			},
			label = { Text(text = stringResource(Res.string.scheduler_minute_label)) },
			isError = !state.isSchedulerMinuteValid,
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.width(100.dp),
		)
	}

	if (state.hasSchedulerTimeError) {
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = buildString {
				if (!state.isSchedulerHourValid) append("Hour must be 0\u201323. ")
				if (!state.isSchedulerMinuteValid) append("Minute must be 0\u201359.")
			}.trim(),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.error,
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

	UnderlinedTextField(
		value = state.schedulerLogFile,
		onValueChange = { value -> onFieldChange { it.copy(schedulerLogFile = value) } },
		label = { Text(text = stringResource(Res.string.label_log_file_optional)) },
		placeholder = { Text(text = stringResource(Res.string.scheduler_log_file_placeholder)) },
		singleLine = true,
		modifier = Modifier.fillMaxWidth(),
	)

	if (state.renewalJobs.isEmpty()) {
		Spacer(modifier = Modifier.height(12.dp))
		Text(
			text = stringResource(Res.string.scheduler_no_jobs_hint),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	} else if (state.schedulerAutoDetectedPath == null && state.schedulerCliPath.isBlank()) {
		Spacer(modifier = Modifier.height(12.dp))
		Text(
			text = stringResource(Res.string.scheduler_no_auto_detect_hint),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.warning,
		)
	}
}








