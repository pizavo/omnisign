package cz.pizavo.omnisign.data.service

/**
 * Process-global, mutable holder for the PKCS#11 probe-subprocess kill timeout, in seconds.
 *
 * `GlobalConfig.pkcs11ProbeTimeoutSeconds` is a plain global knob (it never enters
 * [cz.pizavo.omnisign.domain.model.config.ResolvedConfig] resolution).  The probe-spawning
 * services — [Pkcs11SubprocessProber], [Pkcs11WarmupService] and
 * [Pkcs11LibP11KitModuleResolver] — read [seconds] at spawn time, and the discovery producers
 * (startup warmup, [Pkcs11CacheInvalidator] rediscovery) push the configured value in via
 * [update] each cycle.  This mirrors how `trustedListRefreshIntervalHours` is applied through
 * a settable field on the trusted-list refresh path rather than baked into a constructor at
 * DI time, so a value edited mid-session takes effect on the next discovery without a
 * restart.
 *
 * Held as a single shared Koin singleton so every probe-spawning service observes one value.
 * The value is clamped to [MIN_SECONDS]‥[MAX_SECONDS] on construction and on every [update],
 * matching the range enforced by the CLI and desktop settings.
 *
 * @param initialSeconds Starting timeout before any config is applied; defaults to
 *   [Pkcs11Prober.DEFAULT_PROBE_TIMEOUT_SECONDS].
 */
class Pkcs11ProbeTimeout(initialSeconds: Long = Pkcs11Prober.DEFAULT_PROBE_TIMEOUT_SECONDS) {

	@Volatile
	private var current: Long = initialSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

	/** The current probe timeout in seconds, clamped to [MIN_SECONDS]‥[MAX_SECONDS]. */
	val seconds: Long get() = current

	/**
	 * Replace the current timeout with [newSeconds], clamped to [MIN_SECONDS]‥[MAX_SECONDS].
	 *
	 * Called by the discovery producers after reading
	 * `GlobalConfig.pkcs11ProbeTimeoutSeconds`, so the value an operator persists via the CLI
	 * or desktop settings is honoured on the next probe.
	 *
	 * @param newSeconds The desired timeout; out-of-range values are clamped, not rejected.
	 */
	fun update(newSeconds: Long) {
		current = newSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
	}

	companion object {
		/** Minimum accepted probe timeout, in seconds. */
		const val MIN_SECONDS = 1L

		/** Maximum accepted probe timeout, in seconds. */
		const val MAX_SECONDS = 120L
	}
}
