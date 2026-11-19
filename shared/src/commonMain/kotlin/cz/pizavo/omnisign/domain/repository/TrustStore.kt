package cz.pizavo.omnisign.domain.repository

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate

/**
 * App-managed store of directly-trusted certificates, shared across desktop, CLI, and server.
 *
 * Certificates are kept as content-addressed files (named by their SHA-256 fingerprint) in an
 * app-managed directory, referenced per [TrustScope] through a machine-readable index. A single
 * certificate is stored once and shared across the scopes that reference it; its file is deleted
 * only when no scope references it any more.
 *
 * The trust [TrustedCertificateType] is per reference (per scope), not intrinsic to the
 * certificate - the same certificate may be trusted for different roles in different scopes.
 */
interface TrustStore {
	/**
	 * Whether this store rejects mutation. `false` for the app-managed local store (desktop, CLI,
	 * server); `true` for a read-only remote view — the web client, which reads the trust the server
	 * validates with and cannot modify it. UI edit affordances gate on this so a read-only store never
	 * presents controls whose writes would fail.
	 */
	val readOnly: Boolean get() = false

	/**
	 * Import [certBytes] (PEM or DER) into [scope] with the given [type].
	 *
	 * The bytes are parsed, re-encoded to canonical DER, and stored once under their SHA-256
	 * fingerprint; an already-present certificate is not re-copied. Adding the same certificate to
	 * the scope again replaces its [type].
	 *
	 * @param scope Target scope.
	 * @param certBytes Raw certificate file content (PEM or DER).
	 * @param type Trust role granted in this scope.
	 * @param source Optional provenance recorded in the index (an origin path, or `"inline"`), used
	 *   to resolve the stored copy later if the original source disappears.
	 * @return The stored certificate read model, or an error.
	 */
	suspend fun add(
		scope: TrustScope,
		certBytes: ByteArray,
		type: TrustedCertificateType,
		source: String? = null,
	): OperationResult<TrustedCertificate>

	/**
	 * Remove the certificate with [fingerprint] from [scope].
	 *
	 * The certificate file and index entry are deleted only when no scope references it any more.
	 *
	 * @param scope Scope to remove the reference from.
	 * @param fingerprint Algorithm-prefixed SHA-256 fingerprint of the certificate to remove.
	 * @return Unit on success, or [cz.pizavo.omnisign.domain.model.error.TrustStoreError.NotFound]
	 *   when the scope does not reference it.
	 */
	suspend fun remove(scope: TrustScope, fingerprint: String): OperationResult<Unit>

	/**
	 * List the certificates referenced by [scope].
	 *
	 * @param scope Scope to list.
	 * @return The certificates in the scope, or an error.
	 */
	suspend fun list(scope: TrustScope): OperationResult<List<TrustedCertificate>>

	/**
	 * Parse [certBytes] (PEM or DER) and return its identity and validity without storing it.
	 *
	 * Used to preview a certificate before it is staged for addition — to show its subject and
	 * expiry and to detect whether it is already trusted in a scope (by fingerprint). The returned
	 * model's [TrustedCertificate.type] is [cz.pizavo.omnisign.domain.model.config.TrustedCertificateType.ANY]
	 * as a placeholder; the trust role is assigned per reference when the certificate is added.
	 *
	 * @param certBytes Raw certificate file content (PEM or DER).
	 * @return The parsed certificate read model, or a parse error.
	 */
	suspend fun inspect(certBytes: ByteArray): OperationResult<TrustedCertificate>

	/**
	 * Change the trust [type] of the certificate with [fingerprint] within [scope].
	 *
	 * @return Unit on success, or [cz.pizavo.omnisign.domain.model.error.TrustStoreError.NotFound]
	 *   when the scope does not reference it.
	 */
	suspend fun setType(
		scope: TrustScope,
		fingerprint: String,
		type: TrustedCertificateType,
	): OperationResult<Unit>

	/**
	 * Drop the entire scope of the named profile and garbage-collect any certificate it solely
	 * referenced. A no-op when the profile has no scope.
	 *
	 * @param profileName Profile whose scope is being removed.
	 */
	suspend fun clearProfileScope(profileName: String): OperationResult<Unit>

	/**
	 * Resolve the full trust set for [scope]: the union of the global scope and [scope].
	 *
	 * Every referenced certificate is returned at full trust; the per-reference
	 * [type][ResolvedTrustAnchor.type] travels on each [ResolvedTrustAnchor] for post-validation
	 * policy enforcement rather than filtering the trust input. When a certificate is referenced by
	 * both the global scope and [scope] with different types, the [scope] type takes precedence. A
	 * certificate whose stored copy is missing, damaged or cannot be read now is left out, so that the
	 * others are still used.
	 *
	 * @param scope Scope to resolve.
	 * @return The resolved anchors, or an error.
	 */
	suspend fun resolve(scope: TrustScope): OperationResult<List<ResolvedTrustAnchor>>

	/**
	 * Reference an already-stored certificate (by [fingerprint]) from [scope] with [type], without
	 * supplying its bytes. Adding the reference again replaces its [type].
	 *
	 * Used by the server boot-time reconcile to restore a scope membership when a declared source
	 * file is gone but the content-addressed copy is still in the store. A stored copy that cannot be
	 * read now is still in the store, so it is referenced, and [resolve] leaves it out until it can
	 * be read.
	 *
	 * @return Unit on success, or
	 *   [cz.pizavo.omnisign.domain.model.error.TrustStoreError.NotFound] when no intact copy of the
	 *   certificate with [fingerprint] is stored.
	 */
	suspend fun reference(
		scope: TrustScope,
		fingerprint: String,
		type: TrustedCertificateType,
	): OperationResult<Unit>

	/**
	 * Find the fingerprint of a stored certificate whose recorded provenance includes [source] (an
	 * origin path, or `"inline"`).
	 *
	 * Lets the server boot-time reconcile resolve an unpinned `path` reference to its stored copy
	 * after the original source file has been deleted.
	 *
	 * @param source Provenance string to match against each certificate's recorded sources.
	 * @return The matching fingerprint, or `null` when no stored certificate records [source].
	 */
	suspend fun findBySource(source: String): OperationResult<String?>

	/**
	 * List every scope that currently holds at least one reference.
	 *
	 * Lets the server boot-time reconcile find and clear scopes (for example a removed profile)
	 * that the declarative configuration no longer mentions.
	 *
	 * @return The non-empty scopes, or an error.
	 */
	suspend fun scopes(): OperationResult<Set<TrustScope>>
}

/**
 * Drop the scope of every profile not named in [profileNames], as [TrustStore.clearProfileScope] does for
 * one, so that no profile created later under such a name inherits the certificates a removed profile was
 * trusting. Scopes are keyed by profile name alone, so a scope that outlives its profile would otherwise
 * be picked up again. A read-only store is left as it is.
 *
 * @param profileNames Names of the scopes that are kept: those [ownedProfileScopeNames] gives for the
 *   profiles of the configuration.
 * @return Unit once every other profile scope is gone, or the first error, with the scopes before it
 *   already dropped.
 */
suspend fun TrustStore.clearOrphanedProfileScopes(profileNames: Set<String>): OperationResult<Unit> {
	if (readOnly) return Unit.right()
	val orphaned = scopes().getOrElse { return it.left() }
		.filterIsInstance<TrustScope.Profile>()
		.filter { it.name !in profileNames }
	for (scope in orphaned) clearProfileScope(scope.name).getOrElse { return it.left() }
	return Unit.right()
}

/**
 * The names of the profile trust scopes that the profiles of this map, keyed as
 * [cz.pizavo.omnisign.domain.model.config.AppConfig.profiles] is, own: each key, which the CLI's
 * `config trust` commands check a profile name against, and each profile's own [ProfileConfig.name],
 * whose scope signing and validation resolve. A profile created through the profile use case is keyed by
 * its name, but one in a configuration edited by hand or imported may be keyed otherwise, and then both
 * scopes are its own, so [clearOrphanedProfileScopes] keeps both.
 *
 * @return The names of the scopes the profiles own.
 */
fun Map<String, ProfileConfig>.ownedProfileScopeNames(): Set<String> = keys + values.map { it.name }
