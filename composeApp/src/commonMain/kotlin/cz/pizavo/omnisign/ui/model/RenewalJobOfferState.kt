package cz.pizavo.omnisign.ui.model

import cz.pizavo.omnisign.domain.model.config.RenewalJob

/**
 * State for the renewal job offer shown on the success screen after an LTA-level
 * signing or timestamping operation.
 *
 * @property outputFile Absolute path to the output file that should be covered by a renewal job.
 * @property existingJobs All currently configured renewal jobs.
 * @property availableProfiles Names of profiles available for the profile dropdown.
 * @property activeProfile The currently active profile name, pre-selected by default.
 * @property coveringJob The existing job whose glob patterns already match [outputFile],
 *   or `null` when no such job exists.
 * @property assignedJobName Name of the job the file was assigned to, set after a successful assignment.
 * @property error Human-readable error from the last failed assignment attempt, or `null`.
 */
data class RenewalJobOfferState(
	val outputFile: String,
	val existingJobs: List<RenewalJob> = emptyList(),
	val availableProfiles: List<String> = emptyList(),
	val activeProfile: String? = null,
	val coveringJob: RenewalJob? = null,
	val assignedJobName: String? = null,
	val error: String? = null,
)

