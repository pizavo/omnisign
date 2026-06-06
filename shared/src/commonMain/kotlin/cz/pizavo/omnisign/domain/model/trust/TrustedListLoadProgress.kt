package cz.pizavo.omnisign.domain.model.trust

/**
 * Live progress of loading the EU LOTL's member-state trusted lists during a refresh.
 *
 * Counts the member-state list work only — the tasks that surface *after* the LOTL itself has been
 * downloaded and parsed. While the LOTL is still being fetched (phase 1) no member-state lists are
 * known yet, so [total] is `0` and [fraction] is `null`, signalling that callers should render an
 * indeterminate indicator. Once the lists are scheduled (phase 2), [total] holds their count and
 * [loaded] climbs to it.
 *
 * @property loaded Number of member-state trusted lists that have finished loading this refresh.
 * @property total Number of member-state trusted lists scheduled for this refresh, or `0` before
 *   the LOTL has been parsed (and when no refresh is in flight).
 */
data class TrustedListLoadProgress(
    val loaded: Int = 0,
    val total: Int = 0,
) {
    /**
     * Completion fraction in `0f..1f`, or `null` when [total] is `0` — the caller should render an
     * indeterminate indicator in that case, because the member-state list count is not yet known.
     */
    val fraction: Float?
        get() = if (total <= 0) null else (loaded.toFloat() / total).coerceIn(0f, 1f)
}
