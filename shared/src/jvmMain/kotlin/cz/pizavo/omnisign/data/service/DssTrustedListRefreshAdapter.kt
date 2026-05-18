package cz.pizavo.omnisign.data.service

import cz.pizavo.omnisign.data.repository.TrustedListRefreshSignal
import cz.pizavo.omnisign.domain.model.config.TrustedSourceId
import cz.pizavo.omnisign.domain.port.TrustedListRefreshPort
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * JVM implementation of [TrustedListRefreshPort].
 *
 * Exposes the registry's per-identity refresh signal to multiplatform
 * ViewModels and delegates a manual refresh to [TrustedListRefreshScheduler]
 * (which warms then online-refreshes every distinct source), keeping the manual
 * "Refresh now" path identical to the scheduled cycle.
 */
class DssTrustedListRefreshAdapter(
	private val signal: TrustedListRefreshSignal,
	private val scheduler: TrustedListRefreshScheduler,
) : TrustedListRefreshPort {

	override val running: StateFlow<Set<TrustedSourceId>> get() = signal.running

	override val lastRefreshAt: StateFlow<Instant?> get() = signal.lastRefreshAt

	override suspend fun refreshNow() = scheduler.refreshNow()
}
