package cz.pizavo.omnisign.config

import arrow.core.getOrElse
import cz.pizavo.omnisign.domain.model.config.AppConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateRef
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.encoding.Base64
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
 * - `inline` (Base64 DER, whose line breaks and spaces are ignored) or a readable `path`: import
 *   the bytes; a present `fingerprint` pin that does not match the certificate fails startup before
 *   the certificate is stored.
 * - a `path` whose file is gone: resolve the fingerprint from the pin, else from the stored
 *   provenance, and reference the stored copy (logged at INFO). If neither resolves, fail startup.
 *
 * Any fatal condition is logged at ERROR and rethrown as [IllegalStateException] so the server
 * stops at startup rather than serving with incomplete or wrong trust. A store that cannot be used,
 * such as one whose index cannot be read, is one; its own message says why.
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
	 * Import [bytes] into [scope], checking the optional [TrustedCertificateRef.fingerprint] pin
	 * first: a certificate the pin rejects is never stored, so it never becomes the recorded copy of
	 * its source, which a later boot could fall back on once the source is gone.
	 */
	private suspend fun importBytes(
		scope: TrustScope,
		ref: TrustedCertificateRef,
		bytes: ByteArray,
		source: String,
	): String {
		ref.fingerprint?.let { pinned ->
			val actual = trustStore.inspect(bytes)
				.getOrElse { fatal("read a trusted certificate (source: $source)", it.message) }
				.fingerprint
			if (actual != pinned) {
				fatal(
					"verify a trusted certificate (source: $source)",
					"declared fingerprint $pinned but the certificate is $actual",
				)
			}
		}
		return trustStore.add(scope, bytes, ref.type, source)
			.getOrElse { fatal("import a trusted certificate (source: $source) into ${scope.label()}", it.message) }
			.fingerprint
	}

	/**
	 * Reference a stored certificate when its declared source file is gone, resolving the
	 * fingerprint from the pin or the stored provenance. The failure is put down to a missing copy
	 * only when the store has none; any other gives the store's own message.
	 */
	private suspend fun referenceStored(scope: TrustScope, ref: TrustedCertificateRef): String {
		val fingerprint = ref.fingerprint
			?: trustStore.findBySource(ref.path!!).getOrElse { fatal("look up ${ref.path}", it.message) }
			?: fatal(
				"resolve trusted certificate ${ref.path}",
				"its source file is gone, no fingerprint is pinned, and no stored copy records it",
			)
		trustStore.reference(scope, fingerprint, ref.type).getOrElse { error ->
			fatal(
				"reference stored certificate $fingerprint in ${scope.label()}",
				if (error is TrustStoreError.NotFound) {
					"the source ${ref.path} is gone and no stored copy exists for $fingerprint"
				} else {
					error.message
				},
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

	/**
	 * Decode the Base64 of an [inline] certificate in [scope], ignoring the line breaks and spaces
	 * that a PEM body pasted as a YAML block carries, or that YAML folds a plain multi-line value
	 * into.
	 */
	private fun decodeInline(scope: TrustScope, inline: String): ByteArray =
		runCatching { INLINE_BASE64.decode(inline.filterNot { it.isWhitespace() }) }
			.getOrElse {
				fatal(
					"decode an inline trusted certificate in ${scope.label()}",
					"invalid Base64, even with its line breaks and spaces ignored",
				)
			}

	/**
	 * The file a `path` reference names: [path] itself when it is absolute, else [path] resolved
	 * against [baseDir].
	 */
	private fun resolvePath(baseDir: Path, path: String): Path {
		val candidate = Path.of(path)
		return if (candidate.isAbsolute) candidate else baseDir.resolve(path)
	}

	/**
	 * How the log and the startup errors name this scope.
	 */
	private fun TrustScope.label(): String = when (this) {
		TrustScope.Global -> "the global scope"
		is TrustScope.Profile -> "profile '$name'"
	}

	/**
	 * Log that the reconcile failed to [action] because of [detail], and stop startup with an
	 * [IllegalStateException] that says the same.
	 */
	private fun fatal(action: String, detail: String?): Nothing {
		val message = "Trust reconcile failed to $action: ${detail ?: "unknown error"}"
		logger.error { message }
		throw IllegalStateException(message)
	}

	private companion object {
		/** The decoder of inline certificates, which, like the JDK's basic decoder, accepts Base64 without its padding. */
		val INLINE_BASE64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
	}
}
