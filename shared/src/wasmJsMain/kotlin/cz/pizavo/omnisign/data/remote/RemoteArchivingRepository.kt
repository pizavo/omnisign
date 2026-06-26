package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.api.model.responses.TimestampResultMeta
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.RenewalNeed
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

/**
 * Web-target [ArchivingRepository] implementation that delegates PAdES extension (timestamp /
 * archival) to the OmniSign server.
 *
 * The server owns the TSA configuration; the web client uploads the signed PDF together with
 * the target level and receives the extended PDF back. The renewal-batch surface
 * ([needsArchivalRenewal]) is a desktop/CLI-only filesystem operation with no server route, so
 * it returns an [ArchivingError.ExtensionFailed] "not supported on web" failure.
 *
 * Wire layout:
 * - `POST /api/v1/timestamp` returns the extended PDF as `application/pdf` in the body and a
 *   JSON [TimestampResultMeta] in the `X-OmniSign-Result` header.
 * - `POST /api/v1/timestamp/inspect` returns a serialized [DocumentTimestampInfo].
 *
 * The injected [HttpClient] is expected to have `expectSuccess = true` so non-2xx responses
 * surface as `ResponseException` for the `Either.catch` block to map.
 *
 * @param client Pre-configured Ktor client with kotlinx-serialization content negotiation
 *   installed and a default request URL pointing at the OmniSign server.
 */
class RemoteArchivingRepository(
    private val client: HttpClient,
) : ArchivingRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun extendDocument(parameters: ArchivingParameters): OperationResult<ArchivingResult> =
        Either.catch {
            val response = client.submitFormWithBinaryData(
                url = "api/v1/timestamp",
                formData = formData {
                    append(
                        key = "file",
                        value = parameters.inputBytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"${parameters.inputName}\"")
                        },
                    )
                    append("targetLevel", parameters.targetLevel.name)
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
            val meta: TimestampResultMeta = json.decodeFromString(metaHeader)
            ArchivingResult(
                outputBytes = response.bodyAsBytes(),
                outputName = parameters.inputName,
                newSignatureLevel = meta.newLevel,
                annotatedWarnings = meta.annotatedWarnings,
                rawWarnings = meta.annotatedWarnings.map { it.summary },
            )
        }.mapLeft { exception ->
            ArchivingError.remoteExtensionFailed(details = exception.message, cause = exception)
        }

    override suspend fun needsArchivalRenewal(
        filePath: String,
        renewalBufferDays: Int,
    ): OperationResult<RenewalNeed> =
        ArchivingError.webRenewalUnsupported(
            details = "Renewal jobs scan the local filesystem; the web client has no filesystem access",
        ).left()

    override suspend fun getDocumentTimestampInfo(inputBytes: ByteArray): OperationResult<DocumentTimestampInfo> =
        Either.catch {
            client.submitFormWithBinaryData(
                url = "api/v1/timestamp/inspect",
                formData = formData {
                    append(
                        key = "file",
                        value = inputBytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"document.pdf\"")
                        },
                    )
                },
            ).body<DocumentTimestampInfo>()
        }.mapLeft { exception ->
            ArchivingError.remoteInspectFailed(details = exception.message, cause = exception)
        }
}
