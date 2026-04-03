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
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.*
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.icon_folder
import org.jetbrains.compose.resources.painterResource

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
		Text(
			text = if (state.schedulerInstalled) "✅ Scheduler is installed"
			else "⚪ Scheduler is not installed",
			style = LumoTheme.typography.label1,
			color = if (state.schedulerInstalled) LumoTheme.colors.success
			else LumoTheme.colors.textSecondary,
		)
		InfoTooltip(
			text = "The scheduler is automatically installed when renewal jobs are " +
					"configured, and removed when all renewal jobs are deleted.",
		)
	}

	Spacer(modifier = Modifier.height(16.dp))

	if (state.schedulerAutoDetectedPath != null) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = "Executable:",
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
			label = { Text(text = "OmniSign executable path") },
			placeholder = { Text(text = "/usr/bin/omnisign") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			trailingIcon = {
				TooltipBox(
					tooltip = { Tooltip { Text(text = "Browse") } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { cliFilePicker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_folder),
							contentDescription = "Browse for OmniSign executable",
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
			label = { Text(text = "Hour (0\u201323)") },
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
			label = { Text(text = "Minute (0\u201359)") },
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
		label = { Text(text = "Log file (optional)") },
		placeholder = { Text(text = "/var/log/omnisign-renewal.log") },
		singleLine = true,
		modifier = Modifier.fillMaxWidth(),
	)

	if (state.renewalJobs.isEmpty()) {
		Spacer(modifier = Modifier.height(12.dp))
		Text(
			text = "No renewal jobs configured — the scheduler will be uninstalled on save.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	} else if (state.schedulerAutoDetectedPath == null && state.schedulerCliPath.isBlank()) {
		Spacer(modifier = Modifier.height(12.dp))
		Text(
			text = "Could not auto-detect the executable path. Please specify it manually to enable scheduler installation.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.warning,
		)
	}
}








