package cz.pizavo.omnisign.domain.model.result

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A cached archival-renewal verdict for a single file, letting the scheduler skip the expensive
 * per-file validation while the file is unchanged and not yet due for renewal.
 *
 * @property sizeBytes The file size, in bytes, when it was last validated; a mismatch invalidates
 *   the entry.
 * @property lastModifiedMillis The file's last-modified time, in epoch milliseconds, when it was
 *   last validated; a mismatch invalidates the entry.
 * @property earliestRenewalAt The earliest instant the file will need renewal — the soonest expiry
 *   (signing certificate or cryptographic algorithm) among its uncovered, renewal-relevant
 *   timestamps. The file may be skipped until the renewal buffer reaches this instant. Ignored when
 *   [terminal] is set, since a terminal file has no future due date.
 * @property terminal Whether the file was found past its preservation deadline. Such a file can
 *   never change state on its own, so re-validating it on every run is pure waste; the verdict is
 *   replayed from here until the file itself changes.
 */
@Serializable
data class RenewalCheckCacheEntry(
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val earliestRenewalAt: Instant,
    val terminal: Boolean = false,
)
