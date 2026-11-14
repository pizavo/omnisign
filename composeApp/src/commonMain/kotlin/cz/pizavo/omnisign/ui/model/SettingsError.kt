package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** An error surfaced on the settings dialog, emitted by the VM as locale-agnostic data; the UI resolves it via [resolve]. */
sealed interface SettingsError {
	/** The scheduler run-time fields are out of range. */
	data object SchedulerTimeInvalid : SettingsError

	/** The PKCS#11 probe timeout is outside 1–120 seconds. */
	data object Pkcs11ProbeTimeoutInvalid : SettingsError

	/** The trusted-list refresh interval is below 1 hour. */
	data object TrustedListRefreshIntervalInvalid : SettingsError

	/** The renewal staleness threshold is below 1 day while its warning is on. */
	data object StalenessThresholdInvalid : SettingsError

	/** Refreshing a trusted list failed; [reason] is the underlying cause. */
	data class RefreshFailed(val reason: String) : SettingsError

	/** Installing the OS scheduler failed; [reason] is the underlying cause, or `null` when it gave none. */
	data class SchedulerInstallFailed(val reason: String?) : SettingsError

	/** A domain error carried as localizable [text] (resolved to the active locale by the UI). */
	data class Domain(val text: LocalizableText) : SettingsError
}

/**
 * Resolve this settings error to its localized, human-readable message.
 */
@Composable
fun SettingsError.resolve(): String = when (this) {
	SettingsError.SchedulerTimeInvalid -> stringResource(Res.string.settings_error_scheduler_time_invalid)
	SettingsError.Pkcs11ProbeTimeoutInvalid -> stringResource(Res.string.settings_error_pkcs11_probe_timeout_invalid)
	SettingsError.TrustedListRefreshIntervalInvalid ->
		stringResource(Res.string.settings_error_tl_refresh_interval_invalid)
	SettingsError.StalenessThresholdInvalid -> stringResource(Res.string.settings_error_staleness_threshold_invalid)
	is SettingsError.RefreshFailed -> stringResource(Res.string.settings_error_refresh_failed, reason)
	is SettingsError.SchedulerInstallFailed -> stringResource(
		Res.string.settings_error_scheduler_install_failed,
		reason ?: stringResource(Res.string.settings_error_reason_unknown),
	)
	is SettingsError.Domain -> text.localized()
}
