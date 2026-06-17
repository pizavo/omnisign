package cz.pizavo.omnisign.domain.port

import cz.pizavo.omnisign.domain.model.result.RenewalRunRecord

/**
 * Persists and retrieves the [RenewalRunRecord] for the most recent renewal batch run.
 *
 * The JVM implementation stores it as a small JSON file in the OmniSign config directory. Other
 * platforms (e.g. web) may leave this port unregistered, in which case last-run status is hidden.
 */
interface RenewalRunRecordStore {

	/**
	 * Load the most recent renewal run record.
	 *
	 * @return The persisted record, or `null` when none exists yet or it could not be read.
	 */
	fun load(): RenewalRunRecord?

	/**
	 * Persist [record] as the most recent renewal run record, replacing any previous one.
	 */
	fun save(record: RenewalRunRecord)
}
