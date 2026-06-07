package cz.pizavo.omnisign.domain.port

import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import cz.pizavo.omnisign.domain.model.trust.TrustedListLoadProgress
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Multiplatform seam over the JVM trusted-list registry, exposing just what the
 * desktop ViewModels need to drive refresh UX.
 *
 * Mirrors how `TokenService.discoveryRunning` exposes the PKCS#11 discovery
 * signal to `commonMain` without leaking the JVM/DSS layer. On targets without a
 * DSS backend (web) no implementation is bound; consumers must treat the port as
 * optional and behave as if nothing is ever refreshing.
 */
interface TrustedListRefreshPort {

	/**
	 * The set of trusted-source identities whose retained job is currently being
	 * acquired or refreshed (scheduled cycle, manual refresh, or first lazy load).
	 * Empty when idle. Scoped so a consumer can wait only for the ids it needs.
	 */
	val running: StateFlow<Set<TrustedSourceId>>

	/**
	 * Live progress of loading the trusted lists (the EU LOTL's member-state lists and any custom
	 * lists) for the in-flight refresh. [TrustedListLoadProgress.total] is `0` (render indeterminate)
	 * until the lists are scheduled; it then counts them to completion, resetting to zero between
	 * refreshes. Always `TrustedListLoadProgress()` on targets without a DSS backend.
	 */
	val trustedListLoadProgress: StateFlow<TrustedListLoadProgress>

	/**
	 * When the trusted sources were last successfully refreshed, or `null` if no
	 * refresh has completed yet this process. Drives the "Last refreshed" label.
	 */
	val lastRefreshAt: StateFlow<Instant?>

	/**
	 * Trigger an immediate online refresh of every retained trusted source and
	 * suspend until it completes. Safe to call repeatedly; concurrent callers
	 * coalesce on the registry's per-entry locks.
	 */
	suspend fun refreshNow()
}
