package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * Persisted settings for the OS-level daily renewal scheduler.
 *
 * Stored inside [AppConfig] so that the Compose desktop app (and CLI) can
 * install, update, or remove the OS scheduler job automatically when renewal
 * jobs are added or removed.
 *
 * @property cliExecutablePath Absolute path to the OmniSign executable (CLI or desktop app)
 *   that the scheduler invokes daily. When `null` the scheduler cannot be installed and
 *   the UI prompts the user to specify a path.
 * @property runAtHour Hour of the day (0–23) for the daily run. Default: 2.
 * @property runAtMinute Minute (0–59) for the daily run. Default: 0.
 * @property logFilePath Optional append-only log file for renewal run output.
 */
@Serializable
data class SchedulerConfig(
	val cliExecutablePath: String? = null,
	val runAtHour: Int = 2,
	val runAtMinute: Int = 0,
	val logFilePath: String? = null,
)


