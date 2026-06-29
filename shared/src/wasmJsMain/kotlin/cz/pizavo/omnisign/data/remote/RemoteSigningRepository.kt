package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.api.model.responses.SigningResultMeta
import cz.pizavo.omnisign.domain.model.error.SigningError
import cz.pizavo.omnisign.domain.model.parameters.SigningParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.SigningResult
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.CertificateDiscoveryResult
import cz.pizavo.omnisign.domain.repository.SigningRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

/**
 * Web-target [SigningRepository] implementation that delegates signing to the OmniSign
 * server.
 *
 * The server holds the signing identity (its own PKCS#11 or PKCS#12 keystore configured
 * in `signing.yml`); the web client uploads the document and optionally names a
 * certificate alias. This means three of the four `SigningRepository` methods that exist
 * to drive client-side hardware tokens have no equivalent on the web: [unlockToken] and
 * [loadCertificatesFromFile] return [SigningError.TokenAccessError] with a "not supported
 * on web" message, and the [listAvailableCertificates] `promptForLocked` flag is ignored
 * because the server's certificate discovery is non-interactive by design.
 *
 * Wire layout:
 * - `POST /api/v1/sign` returns the signed PDF as `application/pdf` in the body and a JSON
 *   [SigningResultMeta] in the `X-OmniSign-Result` header.
 * - `GET /api/v1/certificates` returns a serialized [CertificateDiscoveryResult].
 *
 * The injected [HttpClient] is expected to have `expectSuccess = true` so non-2xx responses
 * surface as `ResponseException` for the `Either.catch` block to map.
 *
 * @param client Pre-configured Ktor client with kotlinx-serialization content negotiation
 *   installed and a default request URL pointing at the OmniSign server.
 */
class RemoteSigningRepository(
    private val client: HttpClient,
) : SigningRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun signDocument(parameters: SigningParameters): OperationResult<SigningResult> =
        if (parameters.keystoreFile != null) {
            SigningError.loadFileNotSupportedOnWeb(
                "Signing with a local keystore file is not available on the web target.",
            ).left()
        } else Either.catch {
            val response = client.submitFormWithBinaryData(
                url = "api/v1/sign",
                formData = formData {
                    append(
                        key = "file",
                        value = parameters.inputBytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"${parameters.inputName}\"")
                        },
                    )
                    parameters.certificateAlias?.let { append("certificateAlias", it) }
                    parameters.hashAlgorithm?.let { append("hashAlgorithm", it.name) }
                    parameters.signatureLevel?.let { append("signatureLevel", it.name) }
                    parameters.reason?.let { append("reason", it) }
                    parameters.location?.let { append("location", it) }
                    parameters.contactInfo?.let { append("contactInfo", it) }
                    if (!parameters.addTimestamp) append("noTimestamp", "true")
                    parameters.profileName?.let { append("profile", it) }
                    if (parameters.disabledHashAlgorithms.isNotEmpty()) {
                        append(
                            "disableHashAlgorithm",
                            parameters.disabledHashAlgorithms.joinToString(",") { it.name },
                        )
                    }
                    if (parameters.disabledEncryptionAlgorithms.isNotEmpty()) {
                        append(
                            "disableEncryptionAlgorithm",
                            parameters.disabledEncryptionAlgorithms.joinToString(",") { it.name },
                        )
                    }
                },
            )
            val metaHeader = response.headers["X-OmniSign-Result"]
                ?: error("Server did not return the X-OmniSign-Result header")
            val meta: SigningResultMeta = json.decodeFromString(metaHeader)
            SigningResult(
                outputBytes = response.bodyAsBytes(),
                outputName = parameters.inputName,
                signatureId = meta.signatureId,
                signatureLevel = meta.signatureLevel,
                annotatedWarnings = meta.annotatedWarnings,
                rawWarnings = meta.annotatedWarnings.map { it.summary },
                hasRevocationWarnings = meta.hasRevocationWarnings,
            )
        }.mapLeft { exception ->
            SigningError.remoteSigningFailed(details = exception.message, cause = exception)
        }

    override suspend fun listAvailableCertificates(
        promptForLocked: Boolean,
    ): OperationResult<CertificateDiscoveryResult> =
        Either.catch {
            client.get("api/v1/certificates").body<CertificateDiscoveryResult>()
        }.mapLeft { exception ->
            SigningError.listCertificatesFromServerFailed(details = exception.message, cause = exception)
        }

    override suspend fun unlockToken(tokenId: String): OperationResult<List<AvailableCertificateInfo>> =
        SigningError.tokenUnlockNotSupportedOnWeb(
            details = "Server-side tokens are managed by the server administrator; the web client cannot supply PINs",
        ).left()

    override suspend fun loadCertificatesFromFile(filePath: String): OperationResult<List<AvailableCertificateInfo>> =
        SigningError.loadFileNotSupportedOnWeb(
            details = "Signing on the web is delegated to the server's own keystore; client-side key material is not accepted",
        ).left()
}
