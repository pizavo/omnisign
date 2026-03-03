package cz.pizavo.omnisign.domain.model.config

import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import kotlinx.serialization.Serializable

/**
 * A named, persistent renewal job specifying which B-LTA PDFs to watch and how
 * aggressively to re-timestamp them.
 *
 * Renewal jobs are stored in [AppConfig.renewalJobs] and executed by the `omnisign renew`
 * CLI command, typically triggered by a daily OS-level cron job managed via`omnisign schedule`.
 *
 * @property name Unique identifier for this job.
 * @property globs Glob patterns matching PDF files to inspect on each run.
 * @property renewalBufferDays Days before the timestamp certificate expiry at which
 *   re-timestamping is triggered. Defaults to [ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS].
 * @property profile Named profile for TSA / revocation settings. Falls back to the active
 *   profile when null.
 * @property logFile Absolute path to an append-only log file for renewal output. No log
 *   is written when null.
 * @property notify When true (the default), the OS notification centre alerts the user about
 *   renewal failures and successful re-timestampings. Set to false for headless deployments.
 */
@Serializable
data class RenewalJob(
	val name: String,
	val globs: List<String>,
	val renewalBufferDays: Int = ArchivingRepository.DEFAULT_RENEWAL_BUFFER_DAYS,
	val profile: String? = null,
	val logFile: String? = null,
	val notify: Boolean = true,
)
