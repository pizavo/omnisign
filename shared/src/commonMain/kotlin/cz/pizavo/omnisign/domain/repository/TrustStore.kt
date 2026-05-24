package cz.pizavo.omnisign.domain.repository

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
	 * Every referenced certificate is returned at full trust; the per-reference [type] travels on
	 * each [ResolvedTrustAnchor] for post-validation policy enforcement rather than filtering the
	 * trust input. When a certificate is referenced by both the global scope and [scope] with
	 * different types, the [scope] type takes precedence.
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
	 * file is gone but the content-addressed copy is still in the store.
	 *
	 * @return Unit on success, or
	 *   [cz.pizavo.omnisign.domain.model.error.TrustStoreError.NotFound] when no certificate with
	 *   [fingerprint] is stored.
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
