package cz.pizavo.omnisign.data.remote

import arrow.core.Either
import cz.pizavo.omnisign.domain.model.error.ValidationError
import cz.pizavo.omnisign.domain.model.parameters.ValidationParameters
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.validation.ValidationReport
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/**
 * Web-target [ValidationRepository] implementation that delegates validation to
 * the OmniSign server's `POST /api/v1/validate` endpoint.
 *
 * Posts a `multipart/form-data` request whose `file` part carries
 * [ValidationParameters.inputBytes] under the original [ValidationParameters.inputName]
 * file name, and forwards [ValidationParameters.profileName] plus any per-request
 * algorithm overrides as plain-text form fields so the server can resolve its own
 * configuration against them — `ValidationParameters.resolvedConfig` is therefore
 * ignored here. [ValidationParameters.rawReportFormats] is sent as the `formats`
 * field so the response carries the corresponding raw DSS XML reports for client-side
 * export.
 *
 * The server's `ValidationReport` JSON response is deserialized directly via Ktor's
 * content negotiation: the [HttpClient] supplied by the Koin module installs
 * kotlinx-serialization and `expectSuccess = true`, so a non-2xx response surfaces
 * as a thrown `ResponseException` which this implementation maps to
 * [ValidationError.ValidationFailed]. Configuration of the HTTP client (base URL,
 * JSON settings, success-expectation) belongs in
 * [cz.pizavo.omnisign.di.webDataModule], not here.
 *
 * @param client Pre-configured Ktor client with kotlinx-serialization content
 *   negotiation installed and a default request URL pointing at the OmniSign server.
 */
class RemoteValidationRepository(
    private val client: HttpClient,
) : ValidationRepository {

    override suspend fun validateDocument(parameters: ValidationParameters): OperationResult<ValidationReport> =
        Either.catch {
            client.submitFormWithBinaryData(
                url = "api/v1/validate",
                formData = formData {
                    append(
                        key = "file",
                        value = parameters.inputBytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/pdf")
                            append(HttpHeaders.ContentDisposition, "filename=\"${parameters.inputName}\"")
                        },
                    )
                    parameters.profileName?.let { append("profile", it) }
                    if (parameters.rawReportFormats.isNotEmpty()) {
                        append("formats", parameters.rawReportFormats.joinToString(",") { it.name })
                    }
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
            ).body<ValidationReport>()
        }.mapLeft { exception ->
            ValidationError.ValidationFailed(
                message = "Remote validation failed",
                details = exception.message,
                cause = exception,
            )
        }
}
