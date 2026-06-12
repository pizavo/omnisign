package cz.pizavo.omnisign.ui.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A certificate export option offered by the full-certificate dialog. Each entry pairs a file
 * [extension] with the bytes written for a given DER-encoded certificate: the raw DER for the binary
 * formats (`.cer`, `.der`) or PEM — Base64 wrapped in BEGIN/END CERTIFICATE armor — for the text
 * formats (`.pem`, `.crt`). The two binary entries produce identical bytes (as do the two text
 * entries); they differ only in the file extension a consumer expects.
 *
 * @property label Short description of the encoding, shown in the export menu.
 * @property extension File extension (without a leading dot) for the saved file.
 */
enum class CertificateExportFormat(val label: String, val extension: String, private val pem: Boolean) {
    Cer(label = "DER binary", extension = "cer", pem = false),
    Der(label = "DER binary", extension = "der", pem = false),
    Pem(label = "PEM (Base64)", extension = "pem", pem = true),
    Crt(label = "PEM (Base64)", extension = "crt", pem = true);

    /**
     * Encode [der] (the certificate's raw DER bytes) into this format's file bytes — the DER itself
     * for the binary formats, or PEM text (Base64 in 64-character lines, fenced by the BEGIN/END
     * CERTIFICATE armor) for the text formats.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun encode(der: ByteArray): ByteArray {
        if (!pem) return der
        val body = Base64.encode(der).chunked(PEM_LINE_LENGTH).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n".encodeToByteArray()
    }

    companion object {
        /**
         * The format whose file [extension] matches the given extension (case-insensitive), or
         * `null` when none does. Lets the dialog pick the encoding from the extension the user chose
         * in the native save dialog's type dropdown.
         */
        fun forExtension(extension: String): CertificateExportFormat? =
            entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }

        private const val PEM_LINE_LENGTH = 64
    }
}
