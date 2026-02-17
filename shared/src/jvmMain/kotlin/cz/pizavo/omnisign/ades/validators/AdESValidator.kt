package cz.pizavo.omnisign.ades.validators

import eu.europa.esig.dss.model.FileDocument
import eu.europa.esig.dss.model.policy.ValidationPolicy
import eu.europa.esig.dss.validation.SignedDocumentValidator
import java.io.File
import java.nio.file.Path

open class AdESValidator {
	val validationPolicy: ValidationPolicy? = null
	
	open fun validate(file: File) {
		SignedDocumentValidator.fromDocument(FileDocument(file))
			.apply {
			
			}
			.let {
				validationPolicy?.let { policy -> it.validateDocument(policy) } ?: it.validateDocument()
			}
	}
	
	fun validate(path: Path) = validate(path.toFile())
}