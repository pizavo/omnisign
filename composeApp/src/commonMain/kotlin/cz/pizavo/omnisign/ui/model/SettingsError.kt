package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** An error surfaced on the settings dialog, emitted by the VM as locale-agnostic data; the UI resolves it via [resolve]. */
sealed interface SettingsError {
	/** The scheduler run-time fields are out of range. */
	data object SchedulerTimeInvalid : SettingsError

	/** Refreshing a trusted list failed; [reason] is the underlying cause. */
	data class RefreshFailed(val reason: String) : SettingsError

	/** Installing the OS scheduler failed; [reason] is the underlying cause. */
	data class SchedulerInstallFailed(val reason: String) : SettingsError

	/** A domain error carried as localizable [text] (resolved to the active locale by the UI). */
	data class Domain(val text: LocalizableText) : SettingsError
}

/**
 * Resolve this settings error to its localized, human-readable message.
 */
@Composable
fun SettingsError.resolve(): String = when (this) {
	SettingsError.SchedulerTimeInvalid -> stringResource(Res.string.settings_error_scheduler_time_invalid)
	is SettingsError.RefreshFailed -> stringResource(Res.string.settings_error_refresh_failed, reason)
	is SettingsError.SchedulerInstallFailed -> stringResource(Res.string.settings_error_scheduler_install_failed, reason)
	is SettingsError.Domain -> text.localized()
}
