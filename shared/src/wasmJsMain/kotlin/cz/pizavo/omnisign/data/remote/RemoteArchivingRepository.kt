package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import arrow.core.left
import cz.pizavo.omnisign.api.model.responses.TimestampResultMeta
import cz.pizavo.omnisign.domain.model.config.ResolvedConfig
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.ArchivingError
import cz.pizavo.omnisign.domain.model.parameters.ArchivingParameters
import cz.pizavo.omnisign.domain.model.result.ArchivingResult
import cz.pizavo.omnisign.domain.model.result.DocumentTimestampInfo
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.result.RenewalAssessment
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
                rawWarnings = meta.annotatedWarnings.map { it.summary.english() },
                revocationDataMissing = meta.revocationDataMissing,
                achievedLevel = SignatureLevel.entries.firstOrNull { it.name == meta.newLevel },
            )
        }.mapLeftSuspend { exception -> requestError(exception) { ArchivingError.remoteExtensionFailed(cause = it) } }

    override suspend fun needsArchivalRenewal(
        filePath: String,
        renewalBufferDays: Int,
        resolvedConfig: ResolvedConfig?,
    ): OperationResult<RenewalAssessment> =
        ArchivingError.webRenewalUnsupported(
            details = "Renewal jobs scan the local filesystem; the web client has no filesystem access",
        ).left()

    /**
     * Ask the server to inspect [inputBytes], naming [resolvedConfig]'s profile so that the server
     * judges the level under the trust anchors the extension will run with. Only the name is sent:
     * the server resolves the profile from its own configuration, as it does for [extendDocument].
     * A server rejection the user can act on keeps its keyed message, as it does there.
     */
    override suspend fun getDocumentTimestampInfo(
        inputBytes: ByteArray,
        resolvedConfig: ResolvedConfig?,
    ): OperationResult<DocumentTimestampInfo> =
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
                    resolvedConfig?.profileName?.let { append("profile", it) }
                },
            ).body<DocumentTimestampInfo>()
        }.mapLeftSuspend { exception -> requestError(exception) { ArchivingError.remoteInspectFailed(cause = it) } }

    /**
     * Map a failed request to a localizable [ArchivingError]: a keyed message for a recognized server
     * gating code, otherwise the request's own [fallback] message. The server's raw text is never
     * propagated (no `details`), so no JSON or DSS reason reaches the user.
     *
     * The inspection and the extension share the mapping because the timestamp dialog sends the
     * inspection first: a rejection the user can act on, such as an invalid server configuration,
     * must read the same whichever of the two requests meets it.
     */
    private suspend fun requestError(exception: Throwable, fallback: (Throwable) -> ArchivingError): ArchivingError =
        serverErrorText(exception.serverErrorCode())
            ?.let { ArchivingError.ExtensionFailed(text = it, cause = exception) }
            ?: fallback(exception)
}
