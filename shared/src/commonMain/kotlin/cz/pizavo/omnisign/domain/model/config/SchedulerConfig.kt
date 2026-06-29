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
 * @property stalenessNotificationEnabled Whether to raise an OS notification when renewal has gone
 *   [stalenessThresholdDays] without a successful run, surfacing a scheduler that has silently
 *   stalled (e.g. a stuck lock or a repeatedly failing job). Default: `true`.
 * @property stalenessThresholdDays How many days renewal may go without a successful run before the
 *   staleness notification fires — and re-fires, at most once per this many days, while the problem
 *   persists. Measured as wall-clock time since the last success (idle time counts, since the renewal
 *   buffer expires in real time), but only ever evaluated when a scheduled run actually executes, so a
 *   machine that was simply powered off never trips it. Default: 14.
 */
@Serializable
data class SchedulerConfig(
	val cliExecutablePath: String? = null,
	val runAtHour: Int = 2,
	val runAtMinute: Int = 0,
	val logFilePath: String? = null,
	val stalenessNotificationEnabled: Boolean = true,
	val stalenessThresholdDays: Int = 14,
)


