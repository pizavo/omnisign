package cz.pizavo.omnisign.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.domain.model.config.RenewalJob
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.ButtonVariant
import cz.pizavo.omnisign.lumo.components.Checkbox
import cz.pizavo.omnisign.lumo.components.Chip
import cz.pizavo.omnisign.lumo.components.Icon
import cz.pizavo.omnisign.lumo.components.IconButton
import cz.pizavo.omnisign.lumo.components.IconButtonVariant
import cz.pizavo.omnisign.lumo.components.SelectableContent
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.Tooltip
import cz.pizavo.omnisign.lumo.components.TooltipBox
import cz.pizavo.omnisign.lumo.components.rememberTooltipState
import cz.pizavo.omnisign.lumo.components.textfield.UnderlinedTextField
import cz.pizavo.omnisign.ui.model.GlobChip
import cz.pizavo.omnisign.ui.model.GlobalConfigEditState
import cz.pizavo.omnisign.ui.platform.absoluteGlobExample
import cz.pizavo.omnisign.ui.platform.globNeedsFilePattern
import cz.pizavo.omnisign.ui.platform.globTargetExists
import cz.pizavo.omnisign.ui.platform.isAbsoluteGlob
import cz.pizavo.omnisign.ui.platform.platformFilePath
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings section for managing [RenewalJob] entries.
 *
 * Displays a list of existing renewal jobs with remove buttons, followed by
 * an inline form for adding new jobs. Changes are accumulated locally in
 * [GlobalConfigEditState.renewalJobs] and only persisted when the settings
 * dialog's `Save` button is clicked.
 *
 * @param state Current global config edit state containing the renewal jobs list.
 * @param onFieldChange Called with a transform to update the edit state.
 */
@Composable
fun RenewalJobsSection(
	state: GlobalConfigEditState,
	onFieldChange: ((GlobalConfigEditState) -> GlobalConfigEditState) -> Unit,
) {
	if (state.renewalJobs.isEmpty()) {
		Text(
			text = stringResource(Res.string.renewaljobs_empty),
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.textSecondary,
		)
	} else {
		state.renewalJobs.forEachIndexed { index, job ->
			RenewalJobRow(
				job = job,
				onRemove = {
					onFieldChange {
						it.copy(renewalJobs = it.renewalJobs.toMutableList().apply { removeAt(index) })
					}
				},
			)
			if (index < state.renewalJobs.lastIndex) {
				Spacer(modifier = Modifier.height(8.dp))
			}
		}
	}

	Spacer(modifier = Modifier.height(12.dp))

	RenewalJobAddForm(
		availableProfiles = state.availableProfiles,
		activeProfile = state.activeProfile,
		error = state.renewalJobAddError,
		onClearError = { onFieldChange { it.copy(renewalJobAddError = null) } },
		onError = { message -> onFieldChange { it.copy(renewalJobAddError = message) } },
		onAdd = { job ->
			onFieldChange { current ->
				if (current.renewalJobs.any { it.name == job.name }) {
					current.copy(renewalJobAddError = "A renewal job named '${job.name}' already exists.")
				} else {
					current.copy(
						renewalJobs = current.renewalJobs + job,
						renewalJobAddError = null,
					)
				}
			}
		},
	)
}

/**
 * Single row displaying a configured [RenewalJob] with metadata chips and a remove button.
 *
 * @param job The renewal job to display.
 * @param onRemove Callback invoked when the user clicks the remove button.
 */
@Composable
private fun RenewalJobRow(
	job: RenewalJob,
	onRemove: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.Top,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(text = job.name, style = LumoTheme.typography.label1)
				val profileName = job.profile
				if (profileName != null) {
					ProfileBadge(name = profileName)
				}
			}
			job.globs.forEach { glob ->
				Text(
					text = glob,
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
			}
			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = "Buffer: ${job.renewalBufferDays} days",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
				Text(
					text = "Backups: ${if (job.backupRetention > 0) job.backupRetention.toString() else "off"}",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
				Text(
					text = if (job.notify) "Notify: on" else "Notify: off",
					style = LumoTheme.typography.body2,
					color = LumoTheme.colors.textSecondary,
				)
			}
			if (job.logFile != null) {
				Text(
					text = "Log: ${job.logFile}",
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
				contentDescription = "Remove ${job.name}",
				modifier = Modifier.size(16.dp),
			)
		}
	}
}

/**
 * Blue "info" badge showing a renewal job's profile name.
 *
 * Rendered as a tinted, outlined pill in the theme's info accent ([LumoTheme.colors.tertiary]) so
 * the profile reads as an intentional label rather than a disabled chip.
 *
 * @param name The profile name to display.
 */
@Composable
private fun ProfileBadge(name: String) {
	val shape = RoundedCornerShape(percent = 50)
	val color = LumoTheme.colors.tertiary
	Box(
		modifier = Modifier
			.clip(shape)
			.background(color.copy(alpha = 0.18f))
			.border(width = 1.dp, color = color.copy(alpha = 0.5f), shape = shape)
			.padding(horizontal = 8.dp, vertical = 4.dp),
	) {
		Text(text = name, style = LumoTheme.typography.body2, color = color)
	}
}

/**
 * Inline form for adding a new [RenewalJob].
 *
 * Provides fields for name, glob patterns (comma-separated), buffer days,
 * profile selection, log file path, and notification toggle.
 *
 * The profile dropdown pre-selects the currently active profile so that new
 * jobs inherit the active TSA/revocation settings by default. Selecting
 * "None (global settings only)" stores `null`, which bypasses profile
 * resolution entirely and uses only the global configuration.
 *
 * @param availableProfiles Profile names available for the profile dropdown.
 * @param activeProfile The currently active profile name, pre-selected by default.
 * @param error Human-readable error from the last failed addition attempt, or `null`.
 * @param onClearError Called to dismiss [error] when the user starts a new interaction.
 * @param onError Called with a human-readable message when validation fails.
 * @param onAdd Called with the constructed [RenewalJob] when the user clicks Add.
 */
@Composable
private fun RenewalJobAddForm(
	availableProfiles: List<String>,
	activeProfile: String?,
	error: String?,
	onClearError: () -> Unit,
	onError: (String) -> Unit,
	onAdd: (RenewalJob) -> Unit,
) {
	var name by remember { mutableStateOf("") }
	var globChips by remember { mutableStateOf(listOf<GlobChip>()) }
	var globInput by remember { mutableStateOf("") }
	var globError by remember { mutableStateOf<String?>(null) }
	var bufferDays by remember { mutableStateOf(ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS.toString()) }
	var backupRetention by remember { mutableStateOf(RenewalJob.DEFAULT_BACKUP_RETENTION.toString()) }
	var profile by remember { mutableStateOf(activeProfile) }
	var logFile by remember { mutableStateOf("") }
	var notify by remember { mutableStateOf(true) }

	val errorGlobRequired = stringResource(Res.string.renewaljobs_error_glob_required)
	val errorBufferDaysInvalid = stringResource(Res.string.renewaljobs_error_buffer_days_invalid)
	val errorBackupsInvalid = stringResource(Res.string.renewaljobs_error_backups_invalid)

	val commitGlobs: (String) -> Unit = { text ->
		val (chips, invalid) = parseGlobChips(text, globChips)
		globChips = chips
		globInput = invalid.joinToString(", ")
		globError = invalid.takeIf { it.isNotEmpty() }?.let { globErrorMessage(it) }
	}

	if (error != null) {
		SelectableContent {
			Text(
				text = error,
				style = LumoTheme.typography.body2,
				color = LumoTheme.colors.error,
			)
		}
		Spacer(modifier = Modifier.height(4.dp))
	}

	UnderlinedTextField(
		value = name,
		onValueChange = {
			name = it
			onClearError()
		},
		label = { Text(text = stringResource(Res.string.renewaljobs_field_name_label)) },
		placeholder = { Text(text = stringResource(Res.string.label_job_name)) },
		singleLine = true,
		modifier = Modifier.fillMaxWidth(),
	)

	Spacer(modifier = Modifier.height(8.dp))

	GlobChipField(
		chips = globChips,
		input = globInput,
		error = globError,
		onInputChange = { raw ->
			if (raw.contains(',') || raw.contains(';')) {
				commitGlobs(raw)
			} else {
				globInput = raw
				globError = null
			}
			onClearError()
		},
		onCommit = {
			commitGlobs(globInput)
			onClearError()
		},
		onRemove = { index ->
			globChips = globChips.toMutableList().apply { removeAt(index) }
		},
		onAddGlobs = { paths ->
			val (chips, invalid) = addGlobChips(paths, globChips)
			globChips = chips
			globError = invalid.takeIf { it.isNotEmpty() }?.let { globErrorMessage(it) }
			onClearError()
		},
		onFolderPicked = { glob ->
			globInput = glob
			globError = null
			onClearError()
		},
	)

	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = bufferDays,
			onValueChange = {
				bufferDays = it
				onClearError()
			},
			label = { Text(text = stringResource(Res.string.label_buffer_days)) },
			placeholder = { Text(text = "${ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS}") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.width(120.dp),
		)
		UnderlinedTextField(
			value = backupRetention,
			onValueChange = {
				backupRetention = it
				onClearError()
			},
			label = { Text(text = stringResource(Res.string.renewaljobs_field_backups_label)) },
			placeholder = { Text(text = "${RenewalJob.DEFAULT_BACKUP_RETENTION}") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			modifier = Modifier.width(120.dp),
		)
		DropdownSelector(
			selected = profile,
			options = availableProfiles,
			onSelect = {
				profile = it
				onClearError()
			},
			label = { Text(text = stringResource(Res.string.renewaljobs_field_profile_label)) },
			nullLabel = stringResource(Res.string.label_profile_none),
			showNullOption = true,
			itemLabel = { it },
			modifier = Modifier.weight(1f),
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Bottom,
	) {
		UnderlinedTextField(
			value = logFile,
			onValueChange = {
				logFile = it
				onClearError()
			},
			label = { Text(text = stringResource(Res.string.label_log_file_optional)) },
			placeholder = { Text(text = stringResource(Res.string.renewaljobs_field_log_file_placeholder)) },
			singleLine = true,
			modifier = Modifier.weight(1f),
		)
	}

	Spacer(modifier = Modifier.height(8.dp))

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			modifier = Modifier.weight(1f),
		) {
			Checkbox(
				checked = notify,
				onCheckedChange = { notify = it },
			)
			Text(text = stringResource(Res.string.label_desktop_notifications), style = LumoTheme.typography.body2)
		}
		Button(
			text = stringResource(Res.string.action_add),
			variant = ButtonVariant.PrimaryOutlined,
			enabled = name.isNotBlank() && (globChips.isNotEmpty() || globInput.isNotBlank()),
			onClick = {
				val trimmedName = name.trim()
				val (chips, invalid) = parseGlobChips(globInput, globChips)
				globChips = chips
				globInput = invalid.joinToString(", ")
				val parsedBuffer = bufferDays.toIntOrNull()
				val parsedBackups = backupRetention.toIntOrNull()

				if (invalid.isNotEmpty()) {
					globError = globErrorMessage(invalid)
					return@Button
				}
				if (chips.isEmpty()) {
					onError(errorGlobRequired)
					return@Button
				}
				if (parsedBuffer == null || parsedBuffer <= 0) {
					onError(errorBufferDaysInvalid)
					return@Button
				}
				if (parsedBackups == null || parsedBackups < 0) {
					onError(errorBackupsInvalid)
					return@Button
				}

				onAdd(
					RenewalJob(
						name = trimmedName,
						globs = chips.map { it.glob },
						renewalBufferDays = parsedBuffer,
						profile = profile,
						logFile = logFile.trim().ifBlank { null },
						notify = notify,
						backupRetention = parsedBackups,
					)
				)
				name = ""
				globChips = emptyList()
				globInput = ""
				globError = null
				bufferDays = ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS.toString()
				backupRetention = RenewalJob.DEFAULT_BACKUP_RETENTION.toString()
				profile = activeProfile
				logFile = ""
				notify = true
			},
		)
	}
}

/**
 * Split [text] on `,`/`;`, then partition each non-blank token against [existing]: a duplicate is
 * dropped, a non-absolute glob is collected as invalid (rejected), and an absolute glob becomes a
 * [GlobChip] flagged with whether its target currently exists.
 *
 * @return the resulting chip list (existing plus accepted) and the rejected, non-absolute tokens.
 */
internal fun parseGlobChips(
	text: String,
	existing: List<GlobChip>,
): Pair<List<GlobChip>, List<String>> =
	addGlobChips(text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }, existing)

/**
 * Validate [tokens] against [existing]: drop duplicates, reject non-absolute globs and bare
 * directories (which match no files), and turn each accepted absolute glob into a [GlobChip] flagged
 * with whether its target currently exists. Used directly by the file picker (whose paths are
 * already split) and via [parseGlobChips] for typed or pasted text.
 *
 * @return the resulting chip list (existing plus accepted) and the rejected tokens.
 */
internal fun addGlobChips(
	tokens: List<String>,
	existing: List<GlobChip>,
): Pair<List<GlobChip>, List<String>> {
	val chips = existing.toMutableList()
	val invalid = mutableListOf<String>()
	tokens.forEach { token ->
		when {
			chips.any { it.glob == token } -> Unit
			!isAbsoluteGlob(token) || globNeedsFilePattern(token) -> invalid += token
			else -> chips += GlobChip(
				glob = token,
				warning = when {
					globTargetsNonPdf(token) -> "matches no PDFs (non-PDF extension)"
					!globTargetExists(token) -> "target directory not found"
					else -> null
				},
			)
		}
	}
	return chips to invalid
}

/**
 * The inline error for rejected glob [tokens]: each must be an absolute path ending in a file
 * pattern (a bare directory matches nothing).
 */
private fun globErrorMessage(tokens: List<String>): String =
	"Each glob must be an absolute path with a file pattern (e.g. ${absoluteGlobExample()}): " +
		tokens.joinToString()

/**
 * Whether [glob]'s filename pattern explicitly targets a non-PDF extension (e.g. `*.xml`,
 * `notes.txt`), so it can only ever match non-PDF files. A pattern with no concrete extension (`*`,
 * `report-*`), a `.pdf` extension, or a wildcard inside the extension (`*.{pdf,xml}`) is not flagged.
 */
internal fun globTargetsNonPdf(glob: String): Boolean {
	val name = glob.substringAfterLast('/').substringAfterLast('\\')
	val dotIndex = name.lastIndexOf('.')
	if (dotIndex == -1) return false
	val extension = name.substring(dotIndex + 1)
	if (extension.isEmpty() || extension.any { it == '*' || it == '?' || it == '[' || it == '{' || it == '}' }) {
		return false
	}
	return !extension.equals("pdf", ignoreCase = true)
}

/**
 * Editable list of renewal globs: committed globs render as removable [GlobChip]s (amber when their
 * target directory is missing), and the field below commits its text on Enter or a `,`/`;`
 * delimiter. Non-absolute globs are rejected via [error]; missing-directory globs are accepted but
 * flagged.
 *
 * @param chips The committed globs.
 * @param input The in-progress glob text.
 * @param error An inline error for rejected (non-absolute) globs, or `null`.
 * @param onInputChange Called with the raw field text on every keystroke.
 * @param onCommit Called when the user presses Enter, to commit the current [input].
 * @param onRemove Called with the index of a chip to remove.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobChipField(
	chips: List<GlobChip>,
	input: String,
	error: String?,
	onInputChange: (String) -> Unit,
	onCommit: () -> Unit,
	onRemove: (Int) -> Unit,
	onAddGlobs: (List<String>) -> Unit,
	onFolderPicked: (String) -> Unit,
) {
	val filePicker = rememberFilePickerLauncher(
		type = FileKitType.File(extensions = listOf("pdf")),
		mode = FileKitMode.Multiple(),
	) { files: List<PlatformFile>? ->
		val paths = files?.mapNotNull { platformFilePath(it)?.replace('\\', '/') }.orEmpty()
		if (paths.isNotEmpty()) onAddGlobs(paths)
	}
	val folderPicker = rememberDirectoryPickerLauncher { directory: PlatformFile? ->
		val path = directory?.let { platformFilePath(it) }?.replace('\\', '/')?.trimEnd('/')
		if (path != null) onFolderPicked("$path/**/*.pdf")
	}

	if (chips.isNotEmpty()) {
		FlowRow(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			chips.forEachIndexed { index, chip ->
				GlobChipItem(chip = chip, onRemove = { onRemove(index) })
			}
		}
		Spacer(modifier = Modifier.height(6.dp))
	}

	UnderlinedTextField(
		value = input,
		onValueChange = onInputChange,
		label = { Text(text = stringResource(Res.string.renewaljobs_field_globs_label)) },
		placeholder = { Text(text = absoluteGlobExample()) },
		singleLine = true,
		isError = error != null,
		supportingText = error?.let { message -> @Composable { Text(text = message) } },
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
		keyboardActions = KeyboardActions(onDone = { onCommit() }),
		trailingIcon = {
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				TooltipBox(
					tooltip = { Tooltip { Text(text = stringResource(Res.string.renewaljobs_action_select_files)) } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { filePicker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_file_text),
							contentDescription = stringResource(Res.string.renewaljobs_action_select_files),
							modifier = Modifier.size(18.dp),
						)
					}
				}
				TooltipBox(
					tooltip = { Tooltip { Text(text = stringResource(Res.string.renewaljobs_action_select_folder)) } },
					state = rememberTooltipState(),
				) {
					IconButton(
						modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
						variant = IconButtonVariant.Ghost,
						onClick = { folderPicker.launch() },
					) {
						Icon(
							painter = painterResource(Res.drawable.icon_folder),
							contentDescription = stringResource(Res.string.renewaljobs_action_select_folder),
							modifier = Modifier.size(18.dp),
						)
					}
				}
			}
		},
		modifier = Modifier.fillMaxWidth(),
	)

	val warnings = chips.mapNotNull { it.warning }.distinct()
	if (error == null && warnings.isNotEmpty()) {
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = "Amber globs (allowed — double-check): ${warnings.joinToString("; ")}.",
			style = LumoTheme.typography.body2,
			color = LumoTheme.colors.warning,
		)
	}
}

/**
 * A single renewal glob chip: shows the glob with an × affordance (the whole chip is clickable to
 * remove) and is tinted amber when its target directory is missing.
 *
 * @param chip The glob and its existence flag.
 * @param onRemove Called when the chip is clicked, to remove it.
 */
@Composable
private fun GlobChipItem(chip: GlobChip, onRemove: () -> Unit) {
	val tint = if (chip.warning == null) LumoTheme.colors.onSurface else LumoTheme.colors.warning
	Chip(
		onClick = onRemove,
		label = {
			Text(
				text = chip.glob,
				style = LumoTheme.typography.body2,
				color = tint,
			)
		},
		trailingIcon = {
			Icon(
				painter = painterResource(Res.drawable.icon_x),
				contentDescription = "Remove ${chip.glob}",
				tint = tint,
				modifier = Modifier.size(14.dp),
			)
		},
	)
}



