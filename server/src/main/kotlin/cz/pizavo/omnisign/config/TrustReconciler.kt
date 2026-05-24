package cz.pizavo.omnisign.config

import arrow.core.getOrElse
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readBytes

private val logger = KotlinLogging.logger {}

/**
 * Reconciles the server's content-addressed trust directory with the declarative
 * `trustedCertificates` references in the provider's `signing.yml`, at boot.
 *
 * For each scope (global plus every profile) the declared references are imported into the
 * [TrustStore] and the scope membership is set to exactly what the configuration declares;
 * certificates a scope no longer references are dropped, and the store's reference-counted GC
 * deletes any file no scope references. Scopes the configuration no longer mentions (for example a
 * removed profile) are cleared.
 *
 * Resolution per reference:
 * - `inline` (Base64 DER) or a readable `path`: import the bytes; a present `fingerprint` pin that
 *   does not match the imported certificate fails startup.
 * - a `path` whose file is gone: resolve the fingerprint from the pin, else from the stored
 *   provenance, and reference the stored copy (logged at INFO). If neither resolves, fail startup.
 *
 * Any fatal condition is logged at ERROR and rethrown as [IllegalStateException] so the server
 * stops at startup rather than serving with incomplete or wrong trust.
 *
 * @property trustStore The server's writable trust store.
 */
class TrustReconciler(private val trustStore: TrustStore) {

	/**
	 * Reconcile every scope's trust against [config], resolving relative `path` references against
	 * [baseDir] (the directory containing `signing.yml`).
	 */
	suspend fun reconcile(config: AppConfig, baseDir: Path) {
		val desired = LinkedHashMap<TrustScope, Set<String>>()
		desired[TrustScope.Global] = applyScope(TrustScope.Global, config.global.validation.trustedCertificates, baseDir)
		config.profiles.forEach { (name, profile) ->
			val scope = TrustScope.Profile(name)
			desired[scope] = applyScope(scope, profile.validation?.trustedCertificates.orEmpty(), baseDir)
		}

		val storeScopes = trustStore.scopes().getOrElse { fatal("enumerate trust scopes", it.message) }
		for (scope in storeScopes) {
			val declared = desired[scope]
			if (declared == null) {
				clearScope(scope)
			} else {
				val current = trustStore.list(scope).getOrElse { fatal("list ${scope.label()}", it.message) }
					.mapTo(mutableSetOf()) { it.fingerprint }
				(current - declared).forEach { fingerprint ->
					trustStore.remove(scope, fingerprint)
						.getOrElse { fatal("remove $fingerprint from ${scope.label()}", it.message) }
				}
			}
		}
	}

	/**
	 * Import or reference every declared certificate of [scope], returning the resolved fingerprints.
	 */
	private suspend fun applyScope(
		scope: TrustScope,
		refs: List<TrustedCertificateRef>,
		baseDir: Path,
	): Set<String> {
		val fingerprints = LinkedHashSet<String>()
		for (ref in refs) fingerprints += applyRef(scope, ref, baseDir)
		return fingerprints
	}

	/**
	 * Resolve a single reference into the store, returning its fingerprint.
	 */
	private suspend fun applyRef(scope: TrustScope, ref: TrustedCertificateRef, baseDir: Path): String {
		val hasPath = !ref.path.isNullOrBlank()
		val hasInline = !ref.inline.isNullOrBlank()
		if (hasPath == hasInline) {
			fatal(
				"validate a trusted certificate in ${scope.label()}",
				"each trustedCertificates entry must set exactly one of 'path' or 'inline'",
			)
		}

		return if (hasInline) {
			importBytes(scope, ref, decodeInline(scope, ref.inline!!), source = "inline")
		} else {
			val path = ref.path!!
			val file = resolvePath(baseDir, path)
			if (file.exists()) importBytes(scope, ref, file.readBytes(), source = path)
			else referenceStored(scope, ref)
		}
	}

	/**
	 * Import [bytes] into [scope] and enforce the optional [TrustedCertificateRef.fingerprint] pin.
	 */
	private suspend fun importBytes(
		scope: TrustScope,
		ref: TrustedCertificateRef,
		bytes: ByteArray,
		source: String,
	): String {
		val added = trustStore.add(scope, bytes, ref.type, source)
			.getOrElse { fatal("import a trusted certificate (source: $source) into ${scope.label()}", it.message) }
		if (ref.fingerprint != null && ref.fingerprint != added.fingerprint) {
			fatal(
				"verify a trusted certificate (source: $source)",
				"declared fingerprint ${ref.fingerprint} but the certificate is ${added.fingerprint}",
			)
		}
		return added.fingerprint
	}

	/**
	 * Reference a stored certificate when its declared source file is gone, resolving the
	 * fingerprint from the pin or the stored provenance.
	 */
	private suspend fun referenceStored(scope: TrustScope, ref: TrustedCertificateRef): String {
		val fingerprint = ref.fingerprint
			?: trustStore.findBySource(ref.path!!).getOrElse { fatal("look up ${ref.path}", it.message) }
			?: fatal(
				"resolve trusted certificate ${ref.path}",
				"its source file is gone, no fingerprint is pinned, and no stored copy records it",
			)
		trustStore.reference(scope, fingerprint, ref.type).getOrElse {
			fatal(
				"reference stored certificate $fingerprint in ${scope.label()}",
				"the source ${ref.path} is gone and no stored copy exists for $fingerprint",
			)
		}
		logger.info {
			"Trusted certificate source '${ref.path}' is gone; using the stored copy $fingerprint in ${scope.label()}"
		}
		return fingerprint
	}

	/**
	 * Drop every reference from a scope the configuration no longer mentions.
	 */
	private suspend fun clearScope(scope: TrustScope) {
		when (scope) {
			is TrustScope.Profile ->
				trustStore.clearProfileScope(scope.name).getOrElse { fatal("clear ${scope.label()}", it.message) }

			TrustScope.Global ->
				trustStore.list(scope).getOrElse { fatal("list ${scope.label()}", it.message) }
					.forEach { trustStore.remove(scope, it.fingerprint).getOrElse { e -> fatal("clear global trust", e.message) } }
		}
	}

	private fun decodeInline(scope: TrustScope, inline: String): ByteArray =
		runCatching { Base64.getDecoder().decode(inline) }
			.getOrElse { fatal("decode an inline trusted certificate in ${scope.label()}", "invalid Base64") }

	private fun resolvePath(baseDir: Path, path: String): Path {
		val candidate = Path.of(path)
		return if (candidate.isAbsolute) candidate else baseDir.resolve(path)
	}

	private fun TrustScope.label(): String = when (this) {
		TrustScope.Global -> "the global scope"
		is TrustScope.Profile -> "profile '$name'"
	}

	private fun fatal(action: String, detail: String?): Nothing {
		val message = "Trust reconcile failed to $action: ${detail ?: "unknown error"}"
		logger.error { message }
		throw IllegalStateException(message)
	}
}
