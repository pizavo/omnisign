package cz.pizavo.omnisign.api.model

/**
 * A multipart form field: a part whose `Content-Disposition` names no file.
 *
 * Created by [cz.pizavo.omnisign.api.MultipartFormReader], which keeps the value in memory, bounded
 * together with the other form fields and the part headers of the request.
 *
 * @property name Form field name from the part's `Content-Disposition`, or `null` when the part
 *   names none.
 * @property value The part's content, decoded as UTF-8.
 */
class FormFieldData(
	val name: String?,
	val value: String,
)
