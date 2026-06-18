package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A failure from a renewal-job assignment attempt.
 *
 * The view model emits these as locale-agnostic data; the UI turns them into a localized
 * message via [resolve], so no user-facing wording lives in the view model.
 */
sealed interface RenewalOfferError {
	/** No renewal job named [jobName] exists. */
	data class JobNotFound(val jobName: String) : RenewalOfferError

	/** A renewal job named [jobName] already exists. */
	data class JobAlreadyExists(val jobName: String) : RenewalOfferError
}

/**
 * Resolve this renewal-offer error to its localized, human-readable message.
 */
@Composable
fun RenewalOfferError.resolve(): String = when (this) {
	is RenewalOfferError.JobNotFound -> stringResource(Res.string.renewaloffer_job_not_found, jobName)
	is RenewalOfferError.JobAlreadyExists -> stringResource(Res.string.renewaloffer_job_already_exists, jobName)
}
