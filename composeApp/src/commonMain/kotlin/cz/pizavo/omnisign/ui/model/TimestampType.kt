package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Timestamp operation types presented to the user in the extension dialog.
 *
 * Each type maps to a PAdES target [SignatureLevel] used when invoking the
 * DSS extension operation.
 *
 * @property targetLevel The PAdES level that DSS should extend the document to.
 */
enum class TimestampType(val targetLevel: SignatureLevel) {

	/**
	 * Extend the document to PAdES BASELINE-LT (long-term validation material).
	 *
	 * The concrete change depends on the document's current level: from B-B this adds both a
	 * signature timestamp and revocation data; from B-T it adds only revocation data; from B-LT it
	 * refreshes the revocation data. The dropdown wording reflects this — see [label]. If revocation
	 * data cannot be obtained, the user may be offered a fallback to BASELINE-T.
	 */
	SIGNATURE_TIMESTAMP(SignatureLevel.PADES_BASELINE_LT),

	/**
	 * Add an archival document timestamp.
	 *
	 * Extends the document to PAdES BASELINE-LTA (or renews an existing
	 * LTA document). Always available.
	 */
	ARCHIVAL_TIMESTAMP(SignatureLevel.PADES_BASELINE_LTA);

	/**
	 * Human-readable, action-oriented label for the dropdown, resolved in the current locale.
	 *
	 * The label names the *change* the option performs relative to [currentLevel] rather than the
	 * PAdES target level, so it stays meaningful to users unfamiliar with PAdES terms (e.g. "Add
	 * revocation data" when the document is already B-T).
	 *
	 * @param currentLevel The document's current PAdES level, used to pick the wording.
	 */
	@Composable
	fun label(currentLevel: SignatureLevel): String = when (this) {
		SIGNATURE_TIMESTAMP -> when (currentLevel) {
			SignatureLevel.PADES_BASELINE_B -> stringResource(Res.string.timestamp_option_add_timestamp_revocation)
			SignatureLevel.PADES_BASELINE_T -> stringResource(Res.string.timestamp_option_add_revocation)
			else -> stringResource(Res.string.timestamp_option_refresh_revocation)
		}
		ARCHIVAL_TIMESTAMP -> when (currentLevel) {
			SignatureLevel.PADES_BASELINE_LTA -> stringResource(Res.string.timestamp_option_renew_archival)
			else -> stringResource(Res.string.timestamp_option_add_archival)
		}
	}
}
