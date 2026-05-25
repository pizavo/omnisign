package cz.pizavo.omnisign.data.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Linux resolver: enumerates the PKCS#11 middleware libraries registered with the system's
 * p11-kit, via libp11-kit, and returns their absolute on-disk paths.
 *
 * This is the Linux counterpart to [Pkcs11PcscCalaisResolver] (the Windows PC/SC + Calais
 * resolver): a platform-specific source that hands [Pkcs11CandidateCollector] a list of
 * module **paths** to probe, leaving naming and merging into the candidate set to the
 * caller.  It replaces the previous approach of guessing the `p11-kit-proxy.so` location
 * from a hard-coded path list, which broke whenever a distribution placed the proxy outside
 * those paths.  Rather than loading the aggregating proxy as a single library, it discovers
 * the individual real modules (e.g. `opensc-pkcs11.so`) so each is probed directly — which
 * also sidesteps the proxy's slot renumbering.
 *
 * The libp11-kit call itself runs in an isolated subprocess (see
 * [Pkcs11Prober.discoverModulePaths]); this class adds the policy on top of it: dropping the
 * p11-kit trust policy module (a certificate store, not a signing token) and any path that
 * no longer exists on disk.
 *
 * @property prober Process-isolated worker runner used to spawn the libp11-kit module
 *   discovery subprocess; injected so the native enumeration stays out of the host JVM and
 *   the resolver remains unit-testable.
 * @property probeTimeoutSeconds Wall-clock kill timeout for the discovery subprocess; a
 *   safety net for the unlikely case of a module whose `dlopen` constructor hangs.
 */
class Pkcs11LibP11KitModuleResolver(
	private val prober: Pkcs11Prober = Pkcs11SubprocessProber(),
	private val probeTimeoutSeconds: Long = Pkcs11Prober.DEFAULT_PROBE_TIMEOUT_SECONDS,
) {

	/**
	 * Resolve the absolute paths of p11-kit-registered PKCS#11 modules suitable for signing.
	 *
	 * Delegates enumeration to [Pkcs11Prober.discoverModulePaths] (an isolated libp11-kit
	 * subprocess), then drops the p11-kit trust policy module and any path that does not
	 * exist on disk, and de-duplicates by path.  Naming and merging are the caller's concern,
	 * mirroring [Pkcs11PcscCalaisResolver.resolvePkcs11Paths].
	 *
	 * @return Absolute paths to registered signing-capable PKCS#11 modules; never `null`,
	 *   possibly empty (no p11-kit, no registered modules, or discovery failed).
	 */
	fun resolveModulePaths(): List<String> {
		val discovered = prober.discoverModulePaths(probeTimeoutSeconds)
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.distinct()
		if (discovered.isEmpty()) {
			logger.info { "libp11-kit: no PKCS#11 modules discovered (libp11-kit absent or none registered)" }
			return emptyList()
		}

		val signingModules = discovered.filterNot { isTrustModule(File(it).name) }
		if (signingModules.isEmpty()) {
			logger.info { "libp11-kit: only the p11-kit trust module is registered — no signing modules: $discovered" }
			return emptyList()
		}

		val resolved = signingModules.filter { File(it).exists() }
		if (resolved.isEmpty()) {
			logger.warn {
				"libp11-kit: discovered ${signingModules.size} signing module(s) but none exist on disk " +
						"(are these resolved absolute paths?): $signingModules"
			}
			return emptyList()
		}

		val missingOnDisk = signingModules.filterNot { File(it).exists() }
		if (missingOnDisk.isNotEmpty()) {
			logger.warn { "libp11-kit: ignoring ${missingOnDisk.size} discovered module(s) not present on disk: $missingOnDisk" }
		}
		logger.info { "libp11-kit: resolved ${resolved.size} module path(s) from ${discovered.size} registered: $resolved" }
		return resolved
	}

	/**
	 * Return `true` when [fileName] (base name only) is the p11-kit trust policy module.
	 *
	 * `p11-kit-trust.so` exposes the system's trusted CA certificates as PKCS#11 objects; it
	 * presents a token but holds no signing keys, so probing it would surface a spurious
	 * signing token.  It must never be offered as a signing candidate.
	 */
	private fun isTrustModule(fileName: String): Boolean =
		fileName.lowercase().contains("p11-kit-trust")

	private companion object {
		val logger = KotlinLogging.logger {}
	}
}
