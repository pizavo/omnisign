package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.api.model.responses.TrustedCertificateResponse
import cz.pizavo.omnisign.api.model.responses.toCertificate
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.ResolvedTrustAnchor
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.TrustStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

/**
 * Web-target [TrustStore] that reads directly-trusted certificates from the OmniSign server, so the
 * web client shows exactly the trust the server validates with — never a browser-local store.
 *
 * Only [list] is meaningful: it fetches `GET /api/v1/config/trusted-certificates` (scoped by the
 * `profile` query parameter for a [TrustScope.Profile]) and maps the sanitized
 * [TrustedCertificateResponse] DTOs back into [TrustedCertificate] read models. The store is
 * [readOnly] — the server's trust is provider-authored and immutable over the API — so every mutating
 * (or JVM-only validation) operation returns a [TrustStoreError]. The UI gates its edit affordances on
 * [readOnly], so those paths are not exercised on this target.
 *
 * @param client Pre-configured Ktor client anchored at the OmniSign server (see [webDataModule]).
 */
class RemoteTrustStore(
	private val client: HttpClient,
) : TrustStore {

	override val readOnly: Boolean get() = true

	override suspend fun list(scope: TrustScope): OperationResult<List<TrustedCertificate>> =
		Either.catch {
			val certificates: List<TrustedCertificateResponse> =
				client.get("api/v1/config/trusted-certificates") {
					if (scope is TrustScope.Profile) parameter("profile", scope.name)
				}.body()
			certificates.map { it.toCertificate() }
		}.mapLeft { exception ->
			TrustStoreError.operationFailed(cause = exception)
		}

	override suspend fun add(
		scope: TrustScope,
		certBytes: ByteArray,
		type: TrustedCertificateType,
		source: String?,
	): OperationResult<TrustedCertificate> = readOnlyError()

	override suspend fun remove(scope: TrustScope, fingerprint: String): OperationResult<Unit> = readOnlyError()

	override suspend fun inspect(certBytes: ByteArray): OperationResult<TrustedCertificate> = readOnlyError()

	override suspend fun setType(
		scope: TrustScope,
		fingerprint: String,
		type: TrustedCertificateType,
	): OperationResult<Unit> = readOnlyError()

	override suspend fun clearProfileScope(profileName: String): OperationResult<Unit> = readOnlyError()

	override suspend fun resolve(scope: TrustScope): OperationResult<List<ResolvedTrustAnchor>> = readOnlyError()

	override suspend fun reference(
		scope: TrustScope,
		fingerprint: String,
		type: TrustedCertificateType,
	): OperationResult<Unit> = readOnlyError()

	override suspend fun findBySource(source: String): OperationResult<String?> = readOnlyError()

	override suspend fun scopes(): OperationResult<Set<TrustScope>> = readOnlyError()

	/**
	 * The shared failure for every operation but [list]: this store is a read-only view of the
	 * server's trust and cannot mutate it (nor resolve anchors — the web validates on the server).
	 * These paths are unreachable in normal UI flow because every edit affordance gates on [readOnly].
	 */
	private fun <T> readOnlyError(): OperationResult<T> =
		TrustStoreError.operationFailed(
			details = "The OmniSign server's trust store is provider-authored and read-only over the API",
		).left()
}
