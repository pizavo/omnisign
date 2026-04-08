package cz.pizavo.omnisign.api.model

/**
 * Eagerly buffered representation of a [io.ktor.http.content.PartData.FileItem] whose
 * content has been read into memory during multipart iteration.
 *
 * Created by [cz.pizavo.omnisign.api.collectParts] to avoid the Ktor 3.x issue where
 * [io.ktor.http.content.PartData.FileItem.provider] returns an already-consumed channel
 * after the multipart stream has been fully iterated.
 *
 * @property name Form field name.
 * @property bytes Raw file content.
 */
class FilePartData(
	val name: String?,
	val bytes: ByteArray,
)

