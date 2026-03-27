package cz.pizavo.omnisign.data.util

import kotlin.time.Instant

/**
 * Convert a [java.util.Date] to a [kotlin.time.Instant].
 */
fun java.util.Date.toKotlinInstant(): Instant =
    Instant.fromEpochMilliseconds(this.time)

